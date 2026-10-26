# Remote DAM API

The `com.dbeaver.datadam.dam.remote.api` module contains Remote DAM WebSocket contracts, backend-only GraphQL clients,
and transport-independent messaging packages. A Remote DAM peer can be a Desktop application or a future Managed cluster.
Its session identifies the authenticated DAM transport scope, independently of any database connection.

Java package names are `com.dbeaver.datadam.dam.remote.api.*` and `com.dbeaver.messaging.*`.
See [Messaging](MESSAGING.md) for the neutral request/reply and typed RPC contracts.

## Session identity and handshake

The WebSocket handshake sends `DDRemoteDamHandshakeRequest` with `appDeploymentId`, `appInstanceId`, `product`, and
`version`. `DDRemoteDamHandshakeResponse` returns the assigned `damSessionId`. Wire control types remain `hello` and
`ready`, and the WebSocket path remains `/ws`. Control messages have `type` and an object `payload`; application
messages additionally have `topic`. Application messages may use any nonblank type, including the control type names.

`DDRemoteDamSessionInfo` contains `appDeploymentId`, `appInstanceId`, and `damSessionId`.
`DDRemoteDamSession` adds `product` and `version` for registry presentation. All fields are nonblank strings.
Concurrent sessions may share application deployment and instance IDs; consumers must use `damSessionId` to distinguish
them. Product and version are peer-supplied presentation metadata, not authenticated identity or authorization claims.

## Internal GraphQL contract

`DDRemoteDamClient` posts JSON requests to `/internal/graphql` on the configured relay service root URL.
The service implementation must expose this contract; adding a client alone does not deploy the endpoint.

```graphql
scalar JSON

type Query {
    remoteDamSessions(accountId: ID!): [RemoteDamSession!]!
    remoteDamSessionAccount(damSessionId: ID!): ID
}

type Mutation {
    sendRemoteDamMessage(accountId: ID!, damSessionId: ID!, message: RemoteDamMessageInput!): Boolean!
    registerRemoteDamSubscription(input: RemoteDamSubscriptionInput!): Int!
    unregisterRemoteDamSubscription(subscriberId: String!, subscriptionId: ID!): Boolean!
}

type RemoteDamSession {
    appDeploymentId: String!
    appInstanceId: String!
    damSessionId: ID!
    product: String!
    version: String!
}

input RemoteDamMessageInput {
    topic: String!
    type: String!
    payload: JSON!
}

input RemoteDamSubscriptionInput {
    subscriberId: String!
    subscriptionId: ID!
    accountId: ID!
    damSessionId: ID
    topic: String!
    callbackUrl: String!
    callbackSecret: String!
}
```

- `accountId` is a UUID, mandatory for account-wide session lists, sends, and subscription registration. Sends use the
  account and DAM session ID together.
- `remoteDamSessionAccount` resolves a unique session ID to the account from its verified identity. It returns null
  for a missing or not-ready session; the client reports this as checked `DDRemoteDamException`. This backend-only lookup
  does not authorize end-user access to that account.
- Session lists are immutable client-side snapshots of currently ready transport scopes, not persisted installations
  or session history.
- Message payloads are JSON objects passed through GraphQL variables, never interpolated into query text or encoded as
  JSON strings. `DDRemoteDamMessage` takes defensive payload snapshots.
- A send result of `true` confirms completion of the server's WebSocket write, not peer receipt or processing.
  `false` means no ready session exists for that account and session ID, including a session belonging to another account.
- A session closing after lookup, queue rejection, an invalid message, or a write failure produces a GraphQL error.
  HTTP errors, GraphQL errors including partial data, and malformed responses become `DDRemoteDamException`.
- The client never retries mutations or queues messages offline. After timeout or network failure, delivery may be
  unknown; callers must not blindly replay non-idempotent events.
- Sessions currently live in one relay process. Until owner-aware routing exists, calls must reach the process owning
  the session; a missing result from another replica does not prove global offline status.

## Authentication and configuration

`DDRemoteDamClient` takes a service root URL and the Remote DAM service secret. It never logs in or acquires a peer token.
The URL must use HTTP or HTTPS, have no credentials, query, fragment, or non-root path, and use a valid port.
HTTP is supported for trusted private service networks; use HTTPS across untrusted networks.

The service secret contains 32–512 printable, non-space ASCII characters. It authenticates backend requests and callbacks
through `X-DD-Service-Token`. Subscription registration also carries that same secret in its `callbackSecret` variable;
protect that backend-only request and never log its body. Secrets must not appear in URLs or GraphQL query text.
Redirects are disabled. HTTP establishment uses the shared REST client's 5-second connect timeout; responses have a
10-second deadline. HTTP error bodies, GraphQL error details, and malformed response contents are not copied into client
exceptions.

The secret authenticates a trusted backend, not an end user. Backends exposing operations to users must derive `accountId`
from authorized account context. A DAM session ID alone is not end-user authorization; expose only authorized account
selections. The server validates the secret before internal GraphQL execution and fails closed when it is missing or
invalid. Browser sessions and peer JWTs must not grant access. Keep the service secret in backend runtime configuration;
it is separate from the Account backchannel secret used to verify peer JWTs.

```java
var client = new DDRemoteDamClient(serviceUrl, serviceSecret);
var sessions = client.getSessions(accountId);
boolean written = client.sendMessage(accountId, damSessionId, message);
```

Use one configured client per service endpoint. No deployment-specific URL or secret is packaged here.
`getSessionsAsync`, `getSessionAccountAsync`, `sendMessageAsync`, `registerSubscriptionAsync`, and
`unregisterSubscriptionAsync` use the JDK HTTP client's nonblocking API without a wrapper executor. Synchronous methods
remain available. The deadline includes the response body. Cancelling a stage attempts to stop the HTTP exchange but
does not promise cancellation of an operation already running remotely.

## Backend callback subscriptions

`registerSubscription(subscriberId, subscriptionId, accountId, damSessionId, topic, callbackUrl, callbackSecret)` returns
the lease in seconds. The receiving process generates the UUID `subscriptionId`; repeated registration with the same
scope and callback renews it. A null `damSessionId` subscribes to matching account messages, including future sessions.
A non-null ID filters one physical session and does not follow a replacement session.

Callback service root URLs and secrets follow the same validation as relay URLs and service secrets. Only authenticated
trusted backends may register callbacks. Restrict outbound relay network access and use HTTPS outside trusted networks.
The relay keeps registrations in memory, with no configured subscriber list or separate service-registration step.
The subscriber ID identifies the receiver and does not determine business topics.

`DDRemoteDamCallbackClient` posts to `/internal/remote-dam/graphql`. Callback variables are `subscriptionId`, `accountId`,
`session`, and `message`. The session input is `RemoteDamSessionInfoInput` with `appDeploymentId`, `appInstanceId`, and
`damSessionId`. The mutation is `deliverRemoteDamMessage(subscriptionId: ..., accountId: ..., session: ..., message: ...)`.

The receiver invokes its listener on the HTTP request thread and acknowledges after it returns. Successful acknowledgement
extends the lease. Concurrent callbacks may invoke a listener concurrently without ordering guarantees; listeners must be
thread-safe. The RPC reply carries the business result. `unregisterSubscription` handles cancellation and cleanup of
unknown local registrations. The relay stops starting callbacks on cancellation or expiry; already started callbacks may
finish. Failed callbacks are not replayed or retried. In-flight callbacks are bounded without a per-subscription executor
or mailbox. `deliverAsync` is nonblocking, with a default 30-second deadline including listener execution; explicit
durations are supported. Timeout does not promise remote handler cancellation.

Spring services use the shared `com.dbeaver.datadam.dam.remote.base` server module and its
`DDRemoteDamClientConfiguration`. It supplies callback reception, local dispatch, subscription cancellation, and idle
renewal. Configure `datadam.remote-dam-client.service-url`, `datadam.remote-dam-client.service-secret`,
and `datadam.remote-dam-client.callback-url`. There is no separate callback-secret setting. The configured service secret
is used for requests, callback authentication, and the registration's `callbackSecret` field.

Select `subscriptions.forSession(accountId, damSessionId)` or `subscriptions.forAccount(accountId)`, then subscribe by
topic. The protocol retains account and session scope for ownership checks. Handlers do not renew registrations.
Every callback destination must reach the exact receiving process owning its local registration.
Peer-side subscriptions remain local: authenticated messages are dispatched to local topic listeners without announcing
handlers to the relay.

## Channels and request/reply

`DDRemoteDamChannel.getStatus()` returns immutable `DDRemoteDamChannelStatus` in `DISCONNECTED`, `CONNECTING`, `CONNECTED`,
or `CLOSED` state. Only `CONNECTED` carries `session()` information; `isReady()` tests readiness.
`observeState(observer)` delivers the current state and ordered changes without a snapshot/subscription race. Its returned
subscription closes only that observer. Initial notification can be synchronous; later callbacks run on the notifying
thread. `addSessionListener` remains transitions-only, calling `onConnected` and `onDisconnected` with session information.

`DDRemoteDamRequestReplyTransport` adapts a scoped channel to `MessagingRequestReplyEndpoint<JsonObject>`:

```java
var transport = DDRemoteDamRequestReplyTransport.forChannel(channel, "documents");
try (var requests = MessagingRequestReplyEndpoint.open(transport)) {
    var response = requests.request(payload);
}
```

A backend can instead obtain its transport from `subscriptions.forSession(accountId, damSessionId).requestReply("documents")`.
Open a physical-channel endpoint after readiness. Opening while disconnected or closed fails without retaining
subscriptions. Endpoints can stay installed across reconnects, but pending calls fail at disconnection and are not replayed.
The peer installs `requests.handle(payload -> process(payload))`. Use distinct topics for independently handled services.
Each endpoint installs its private reply route before accepting calls; replies return to the originating backend process.
Null responses represent successful completion without a value. This lower layer exposes protocol error codes without
application exception details; typed RPC adds its own diagnostics.

The bridge message type remains `datadam.requestreply.v1`. Envelopes contain `requestId`, `replyTo`, `type`, and either
`data` or `error`. `replyTo` is an endpoint UUID, not a backend address. Private topics remain
`datadam.reply.v1.<SHA-256(request-topic)>.<endpoint-UUID>` and fit metadata limits for maximum-length topics.
Closing an endpoint removes both receiving routes and owned work without closing a shared channel or subscription facade.
Backend request/reply is exposed only for `forSession`, with no implicit selection from account subscriptions.

`request` blocks a worker or virtual thread. Ordinary sends and HTTP callback acknowledgements have transport-only
meaning. Backend sends use nonblocking HTTP, limited to 128 in flight without a send pool or waiting queue. Facade closure
rejects new sends and invalidates later completions; an already started HTTP send can finish. Physical channels notify
disconnect immediately; backend callbacks have no physical session-state feed, so pending calls expire by deadline.
Timeout or interruption can leave remote outcome unknown; requests and responses are never automatically retried.

The direct-channel adapter orders messages and disconnections through one bounded receiving mailbox. Source validation
and mailbox insertion share a lifecycle lock; callbacks run outside it. A newly visible source inserts its generation
boundary before its first message. Delayed observers read current status rather than reverting to an old source.
The mailbox holds 128 callbacks without an executor. Excess frames are dropped; disconnection takes priority over a full
batch. Listener failure closes the receiving routes. Backend callbacks invoke listeners directly on concurrent HTTP threads.
The neutral endpoint owns no executor. Blocking handlers must stay off a serialized receiver needed for their replies.

## Typed RPC

`com.dbeaver.messaging.rpc` supplies typed proxies and implementation registration independently of DataDam and WebSocket.
`DDRemoteDamRpcTransport` adapts request/reply to `MessagingRpcMessage<JsonElement>`. Application serialization is selected
through a codec, for example `new DDRemoteDamGsonRpcCodec(new Gson())`. The codec retains the application's Gson adapters
and naming policies, preserves declared generic types, and handles each argument independently. A varargs array is one
argument; JSON null is a nullable payload. Each proxy and registration can use a different compatible codec.

```java
try (var rpc = MessagingRpcEndpoint.open(DDRemoteDamRpcTransport.forChannel(channel), codec);
     var registration = rpc.register("dam.projects", DamProjects.class, implementation)) {
    ServerDocuments documents = rpc.proxy("server.documents", ServerDocuments.class);
    serveApplication(documents);
}
```

Backend scopes wrap `scope.requestReply(topic)` in `DDRemoteDamRpcTransport` and use the same endpoint API. An optional
borrowed `requestExecutor` runs admitted incoming requests; otherwise the receiving thread runs them. Responses and
disconnects never use that executor. Application/platform owners must keep blocking handlers off WebSocket delivery.
The transport owns no execution service.

The developer's topic is the actual request topic. Routes open lazily and are locally reused, with no automatic Account,
Storage, or service prefix. Registering locally does not register a remote service. Arrange the intended receiver's
subscription before calling; duplicate backend receivers are not service discovery. Exact callback-owner routing still
applies.

Backend `DDRemoteDamRpc` owners expose `getSessions(accountId)`, `forSession(damSessionId)`, and `closeSession(damSessionId)`.
Listing returns an immutable snapshot without opening endpoints. Selection is application-owned; opening resolves the
account through the authenticated relay API and preserves ownership checks. Known session IDs work without listing or
local discovery caches. Repeated lookup shares an open endpoint; identifiers are not repeated on each business call.
Session owners provide a default `DDRemoteDamGsonRpcCodec`; explicit codec overloads override it for one binding only.
Proxy timeout overloads accept `Duration`, without changing other proxies or the shared session; the default is 30 seconds
unless owner options specify otherwise.

Obtain an endpoint from the application's ready session owner and pass it to ordinary objects. Endpoint access does not
start login or wait for readiness. Register implementations at runtime and retain their registration handles until they
should stop accepting calls. Proxies need no separate close. The session owner owns the shared endpoint. Transient
reconnects retain endpoints and registrations; authentication identity changes and shutdown invalidate them. Replacing a
closed endpoint does not transfer registrations. Backend owners close missing sessions after successful reconciliation;
failed registry reads preserve endpoints. Asynchronous opening results are shared while callers wait with a timeout.
Reconciliation and renewal use platform scheduling, without owner-created executors. Owners and caches remain process-local.

The inner envelope remains `protocol: "dbeaver.rpc.v2"`, `type`, and `data`, with `operation` on requests or a fixed `error`
code on failures. Request data is positional: `[]`, `[value]`, `[null]`, or `[value1, value2]`; varargs can be `[["a", "b"]]`.
Error `exception` is null or an object with string `type`, nullable `message`, string `stackTrace`, and boolean `truncated`.
Causes and suppressed exceptions are formatted remotely; no `Throwable` is serialized and application codecs are not used.
Each argument and response can be any JSON value, while the outer topic message remains an object. Existing correlation,
source validation, and authentication still apply. Malformed envelopes are ignored without exposing raw contents.

Framing, snapshot copying, and outstanding sends are bounded to 128 operations without a waiting queue. Cancellation does
not release a send slot until its underlying operation finishes. Business contracts use ordinary synchronous methods;
`void` waits for completion. Failures become unchecked `MessagingRpcException`; remote handler/codec failures preserve
their codes and bounded `remoteException()` diagnostics, also exposed through a local diagnostic cause for logging.
Bounds are 256 UTF-16 units for type, 1,024 for message, and 8,192 for trace. `truncated` marks incomplete details; code-only
errors have null diagnostics. See [Messaging](MESSAGING.md) for contract shapes, concurrency, ownership, and timeout semantics.
