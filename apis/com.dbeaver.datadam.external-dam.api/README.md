# External DAM API

The `com.dbeaver.datadam.external-dam.api` module contains the Desktop WebSocket contracts, a backend-only client for
the External DAM internal GraphQL API, and the transport-independent messaging packages.
Neither the models nor the WebSocket protocol depend on the HTTP client, Spring, or Eclipse runtime services.

Java package names remain `com.dbeaver.datadam.external.api.*` and `com.dbeaver.messaging.*`.
See [Messaging](MESSAGING.md) for the request/reply and typed RPC contracts.

## Internal GraphQL contract

The backend client posts JSON requests to `/internal/graphql` on the configured External DAM service root URL.
The service implementation must expose the following contract; adding the client alone does not deploy the endpoint.

```graphql
scalar JSON

type Query {
    externalDamConnections(accountId: ID!): [ExternalDamConnection!]!
    externalDamConnectionAccount(connectionId: ID!): ID
}

type Mutation {
    sendExternalDamMessage(accountId: ID!, connectionId: ID!, message: ExternalDamMessageInput!): Boolean!
    registerExternalDamSubscription(input: ExternalDamSubscriptionInput!): Int!
    unregisterExternalDamSubscription(subscriberId: String!, subscriptionId: ID!): Boolean!
}

type ExternalDamConnection {
    deploymentId: String!
    applicationRunId: String!
    connectionId: ID!
    product: String!
    version: String!
}

input ExternalDamMessageInput {
    topic: String!
    type: String!
    payload: JSON!
}

input ExternalDamSubscriptionInput {
    subscriberId: String!
    subscriptionId: ID!
    accountId: ID!
    connectionId: ID
    topic: String!
    callbackUrl: String!
    callbackSecret: String!
}
```

- `accountId` is a UUID and is mandatory for account-wide connection lists, sends, and subscription registration.
  Sending must use the account and connection ID together.
- `externalDamConnectionAccount` resolves a unique physical connection ID to the account from its verified identity.
  It returns null for a missing or not-ready connection. The backend client reports that result as a checked
  `DDExternalDamException`. This backend-only lookup does not authorize end-user access to that account.
- Connection lists return an immutable client-side snapshot of currently ready sockets, not persisted DAM installations or history.
- Concurrent sockets can share a Deployment ID and application run ID. Consumers must not deduplicate sockets using either ID.
- Product and version are Desktop-supplied presentation metadata, not authenticated identity or authorization claims.
- Message payloads must be JSON objects. They are passed through GraphQL variables, not interpolated into query text or encoded as JSON strings.
- A send result of `true` means the server completed its WebSocket write. It does not confirm that Desktop received or processed the event.
- A result of `false` means that no ready connection exists for that account and connection ID. This also covers a connection belonging to another account.
- A connection closing after lookup, queue rejection, an invalid message, or a write failure produces a GraphQL error rather than a successful send.
- HTTP errors, GraphQL errors (including errors with partial data), and malformed responses become `DDExternalDamException`.
- The client does not retry mutations or queue messages offline. After a timeout or network failure, delivery may be unknown; callers must not blindly replay non-idempotent events.
- Connections live in one External DAM process. Until owner-aware routing exists, calls must reach the process owning the socket; a missing result from another replica does not prove global offline status.

## Authentication and configuration

`DDExternalDamClient` takes a service root URL and the External DAM service secret. It never logs in or acquires a Desktop token.
The URL must use HTTP or HTTPS, have no credentials, query, fragment, or non-root path, and use a valid port.
HTTP is supported for trusted private service networks; use HTTPS across untrusted networks.

The External DAM service secret must contain 32–512 printable, non-space ASCII characters. It authenticates
both backend-to-broker requests and broker-to-backend callbacks through the `X-DD-Service-Token` header.
Subscription registration also carries this same secret in its `callbackSecret` variable for protocol compatibility;
protect that backend-only request and never log its body. The secret must never appear in URLs or GraphQL query text.
Redirects are disabled to avoid forwarding credentials to another endpoint.
Connection establishment uses the shared REST client's 5-second default connect timeout; responses have a 10-second deadline.
HTTP error bodies, GraphQL error details, and malformed response contents are not copied into client exceptions.

The service secret authenticates a trusted backend, not an end user. A backend exposing these operations to users must
derive `accountId` from its authorized account context rather than trusting an arbitrary account ID supplied by the caller.
Likewise, a connection ID alone is not end-user authorization; only expose selections from an authorized account's list.
The server must validate the service secret before executing internal GraphQL operations and fail closed when its secret
is missing or invalid. A browser session or Desktop JWT must not grant access to this endpoint.
The secret belongs to backend runtime configuration only and must never be distributed to Desktop or browser clients.
It is separate from the Account backchannel secret used to verify Desktop JWTs.

```java
var client = new DDExternalDamClient(serviceUrl, serviceSecret);
var connections = client.getConnections(accountId);
boolean written = client.sendMessage(accountId, connectionId, message);
```

Use one configured client per service endpoint instead of constructing one per request. No deployment-specific URL or secret is packaged here.
The client also exposes `getConnectionsAsync`, `getConnectionAccountAsync`, `sendMessageAsync`,
`registerSubscriptionAsync`, and `unregisterSubscriptionAsync`. These use the JDK HTTP client's nonblocking API and
return completion stages without a wrapper executor; synchronous methods remain available to ordinary callers.
The deadline covers the full HTTP exchange, including the response body. Cancelling a stage attempts to stop the
HTTP exchange, but does not promise cancellation of an operation already running on the receiving service.

## Backend callback subscriptions

`registerSubscription(subscriberId, subscriptionId, accountId, connectionId, topic, callbackUrl, callbackSecret)`
returns the lease in seconds. The receiving process generates the UUID `subscriptionId`; repeated registration
with the same scope and callback renews it.
A null `connectionId` subscribes to the account's matching messages, including future Desktop connections.
A non-null connection ID filters one physical connection and does not follow a replacement socket.
The receiver supplies its callback service root URL and the External DAM service secret as `callbackSecret` during
registration and renewal. The `callbackSecret` parameter and wire field remain part of the registration protocol.
The broker keeps them only with its in-memory registrations; it has no configured subscriber list or separate
service-registration step. The subscriber ID identifies the receiver and does not determine business topics.
Callback URLs follow the same HTTP/HTTPS root-URL validation as the broker URL, and callback secrets follow the same
32–512 printable, non-space ASCII validation as the service secret. Only trusted authenticated backends may register
callbacks; restrict the broker's outbound network access and use HTTPS outside a trusted private network.

`DDExternalDamCallbackClient` delivers messages to the receiver's authenticated
`POST /internal/external-dam/graphql` using the same External DAM service secret. Successful acknowledgement extends the
lease. The receiver calls its listener directly on the HTTP request thread and acknowledges after it returns.
Concurrent callbacks may invoke the same listener concurrently and have no ordering guarantee; implementations must
be thread-safe. An RPC reply, not the HTTP acknowledgement, carries the business result. Local cancellation
uses `unregisterSubscription`, including cleanup when the receiver no longer recognizes an incoming registration.
External DAM stops starting callbacks on cancellation or expiry; an already started callback may finish.
Messages are not retried or replayed after callback failure. The broker bounds in-flight callbacks per subscription;
neither side creates a delivery executor or per-subscription mailbox. `deliverAsync` sends callbacks using nonblocking
HTTP. Its default deadline is 30 seconds, including listener execution, and an explicit duration remains supported.
Timeout does not promise cancellation of the remote handler.

Spring services use the shared `com.dbeaver.datadam.external.base` module from the DataDam server repository.
It supplies the callback endpoint, local dispatch, `DDSubscription.close()` cancellation, and automatic renewal
during idle periods. Configure `datadam.external-dam-client.service-url`, `datadam.external-dam-client.service-secret`, and
`datadam.external-dam-client.callback-url` on Account and Storage. Set `service-secret` to the External DAM service
secret; the library uses it for outgoing requests, incoming callback authentication, and the registration's
`callbackSecret` field automatically when opening topic subscriptions, including RPC request/reply routes.
There is no separate `datadam.external-dam-client.callback-secret` configuration.
Select a scoped channel once with `subscriptions.forConnection(accountId, connectionId)` or
`subscriptions.forAccount(accountId)`, then call `channel.subscribe(topic, listener)` without repeating identifiers.
The low-level registration protocol retains account and connection scope to enforce account ownership.
Application handlers do not renew registrations themselves. Every callback destination must reach the exact
receiving process that owns the local registration.

Desktop subscriptions remain local. The broker sends to the selected authenticated physical Desktop connection;
the Desktop channel dispatches the message to its local topic listeners without announcing handlers to the broker.

## Channel state

`DDMessageChannel.getStatus()` returns an immutable `DDChannelStatus`: `DISCONNECTED`, `CONNECTING`, `CONNECTED`,
or `CLOSED`. Only `CONNECTED` contains connection information, and `status.isReady()` checks that state.
`observeState(observer)` delivers the current state and subsequent changes in order without a snapshot/subscription
race. Closing the returned subscription cancels only that observer. Initial notification can run synchronously;
subsequent callbacks run on the notifying thread. Existing `addConnectionListener` remains transitions-only.

## Request/reply

The `com.dbeaver.messaging.requestreply` package supplies `RequestReplyEndpoint<P>`. It knows nothing about DataDam
accounts, deployment IDs, GraphQL, or JSON. `DDRequestReplyTransport` adapts an existing scoped DataDam channel to
`RequestReplyEndpoint<JsonObject>` without changing the channel's asynchronous message API.

On Desktop, or inside External DAM over an existing physical channel:

```java
var transport = DDRequestReplyTransport.forChannel(channel, "documents");
try (var requests = RequestReplyEndpoint.open(transport)) {
    var response = requests.request(payload);
}
```

Open a physical-channel endpoint after the channel becomes ready. Opening over a disconnected or closed physical
channel fails without retaining subscriptions. An already opened endpoint can stay installed across reconnects;
requests outstanding at disconnection fail and are not replayed.

An Account, Storage, or other backend selects a physical connection once:

```java
var transport = subscriptions.forConnection(accountId, connectionId).requestReply("documents");
try (var requests = RequestReplyEndpoint.open(transport)) {
    var response = requests.request(payload);
}
```

The peer registers `requests.handle(payload -> process(payload))` on its own endpoint. The request topic identifies
one logical receiving endpoint for the selected peer; use distinct topics for independently handled services. Each
endpoint establishes its private reply subscription before accepting calls, so replies return to the originating
backend process. Protocol envelopes and payloads remain JSON objects. A null response represents successful completion
without a result. Handler failures are returned as safe protocol errors, without application exception details.

The bridge uses message type `datadam.requestreply.v1`. Its envelope contains `requestId`, `replyTo`, `type`, and either
`data` or `error`. `replyTo` is an endpoint UUID, not a backend address. Private reply topics use
`datadam.reply.v1.<SHA-256(request-topic)>.<endpoint-UUID>`, which keeps them within registration metadata limits even
for a maximum-length request topic. Closing the endpoint cancels both topic subscriptions.

`request` blocks a worker or virtual thread. The request/reply result acknowledges remote handler completion;
ordinary channel sends and callback GraphQL acknowledgements have transport-only meaning. The backend
adapter uses nonblocking HTTP with at most 128 sends in flight, without a send pool or waiting queue. It keeps the existing
in-memory callback subscriptions and lease renewal, and exposes request/reply only for `forConnection`, with no implicit connection
selection from account-wide subscriptions.

Closing the request/reply endpoint cancels its receiving subscriptions and owned work, without closing the shared
channel or subscription facade. Closing the subscription facade rejects new backend sends and invalidates later send
completions; an already started HTTP send can finish. Physical channels report disconnects immediately; backend
callbacks have no physical connection-state feed, so pending calls use their deadlines when the peer disappears.
Timeout or interruption can leave the remote outcome unknown. No request or response is automatically retried.

The direct-channel adapter orders message callbacks and disconnection callbacks through one bounded receiving mailbox.
Source validation and mailbox insertion share the same lifecycle lock; listener callbacks run outside that lock.
If a new physical source is visible before its state observer runs, the adapter inserts the generation boundary before
that source's first message. Delayed observers read the current channel status instead of reverting to an old source.
The mailbox holds at most 128 queued callbacks and adds no executor. Excess frames are dropped; a disconnection takes
priority over a full queued batch so pending calls are still invalidated. Listener failure closes the receiving routes.
Backend callback adapters have no serialized receiving mailbox and invoke listeners directly on concurrent HTTP threads.
The neutral request/reply endpoint owns no executor. A low-level physical-channel user must keep blocking handlers
off a serialized receiver when they can make synchronous calls whose replies need that same receiver.

## Typed RPC

`com.dbeaver.messaging.rpc` adds typed Java proxies and registered implementations without depending on DataDam or
WebSocket. `DDRpcTransport` adapts the existing request/reply transport to the neutral `RpcMessage<JsonElement>` envelope.
It owns no execution service or application serializer; the developer selects serialization through `RpcCodec<JsonElement>`.

For example, a plugin can supply its own Gson instance and adapters for its proxies and implementations:

```java
Gson gson = new Gson();
RpcCodec<JsonElement> codec = new DDGsonRpcCodec(gson);
```

The Desktop side installs an implementation and can independently obtain proxies for backend services:

```java
try (var rpc = RpcEndpoint.open(topic -> DDRpcTransport.forChannel(channel, topic, requestExecutor));
     var registration = rpc.register("desktop.projects", DesktopProjects.class, implementation, codec)) {
    ServerDocuments documents = rpc.proxy("server.documents", ServerDocuments.class, codec);
    serveApplication(documents);
}
```

`requestExecutor` is a borrowed application/platform execution service, used only for admitted incoming requests.
Without that argument, the adapter uses its receiving thread. Responses and disconnects are never dispatched through
the request executor. Prefer the ready Desktop connection service below; it uses Eclipse system Jobs so blocking
handlers do not stall WebSocket reply delivery, without creating RPC-owned thread pools.

The backend selects its physical account/connection scope once and uses the same endpoint API:

```java
var scope = subscriptions.forConnection(accountId, connectionId);
try (var rpc = RpcEndpoint.open(topic -> {
    try {
        return new DDRpcTransport(scope.requestReply(topic));
    } catch (DDExternalDamException e) {
        throw new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e);
    }
});
     var registration = rpc.register("server.documents", ServerDocuments.class, implementation, codec)) {
    DesktopProjects projects = rpc.proxy("desktop.projects", DesktopProjects.class, codec);
    serveApplication(projects);
}
```

The topic supplied to `register` and `proxy` is the actual DataDam request topic. Topic-scoped request/reply routes
open lazily and are reused locally. The mechanism adds no Account/Storage service routing or automatic prefix; naming
conventions such as `storage.files` are entirely application-owned. Registering a local service never registers one
on the other peer. The existing private reply topic routes each answer back to the exact initiating process.
The application must arrange the intended receiver's subscription before calling it; duplicate backend receivers on
the same topic are not service discovery. Replica routing and exact callback-process ownership constraints still apply.

`DDGsonRpcCodec` retains the supplied `Gson` instance, including application adapters and naming policies, and preserves
the declared generic `Type` passed by RPC. Encoded JSON null is represented as a nullable application payload. The
neutral messaging packages remain independent of Gson, and each proxy or registration can supply another compatible codec.
The resulting `RpcEndpoint` is not parameterized by JSON or by any other payload representation.

### Ready endpoints in Account, Storage, and Desktop

Account and Storage already import `DDExternalDamClientConfiguration` from `com.dbeaver.datadam.external.base`.
That configuration supplies the callback receiver, subscriptions, and a shared
`DDExternalDamRpc` connection owner. Inject that owner once, select a Desktop connection, and obtain its endpoint:

```java
var connections = rpcConnections.getConnections(accountId);
var selected = chooseConnection(connections);
RpcEndpoint rpc = rpcConnections.forConnection(selected.connectionId());
```

`rpcConnections` is the `DDExternalDamRpc` Spring bean. `getConnections(accountId)` returns an immutable current
snapshot without opening RPC endpoints; `chooseConnection` represents application logic or a user selection from
those descriptions. Selection and release require only the unique physical connection ID, with no account wrapper.
The owner resolves the account through the authenticated private relay API when opening an endpoint, so a known
connection ID also works without listing connections first. No local discovery cache is required. The transport
retains the resolved account internally and preserves account-ownership checks for subscriptions and sends.
The configured subscriber ID identifies the callback destination and does not select RPC topic names.
Repeated lookups return the same open endpoint; callers do not construct adapters or pass account/connection IDs
to each registration or remote call.

The Desktop and backend connection owners provide an uncustomized `DDGsonRpcCodec` as a default, so ordinary
contracts can use `rpc.proxy(topic, Contract.class)` or `rpc.register(topic, Contract.class, implementation)`.
The overloads accepting a codec override it only for that proxy or implementation registration, without modifying
another caller's serialization or requiring a shared application codec bean.
Callers can also override the response timeout when creating a proxy with `rpc.proxy(topic, Contract.class, timeout)`
or `rpc.proxy(topic, Contract.class, codec, timeout)`, where `timeout` is a `Duration`. Other proxies and their shared
connection keep their existing settings; overloads without a timeout use the endpoint default of 30 seconds unless
the owner supplied different options.

Desktop obtains the corresponding endpoint from its existing connection service:

```java
var connections = DDDesktopConnectionService.getInstance();
RpcEndpoint rpc = connections.getRpc();
```

The Desktop connection must first become ready. Endpoint access never starts login or waits for a new connection.
The endpoint can serve independently chosen topics over the same WebSocket, with no reserved backend-service lanes.

Pass the obtained endpoint to ordinary Java objects. The receiving object registers an implementation at runtime:

```java
public final class GreetingReceiver {
    private final RpcEndpoint rpc;
    private final RpcCodec<JsonElement> codec;

    public GreetingReceiver(RpcEndpoint rpc, RpcCodec<JsonElement> codec) {
        this.rpc = rpc;
        this.codec = codec;
    }

    public MessageSubscription enable(Greeting implementation) {
        return rpc.register("greeting", Greeting.class, implementation, codec);
    }
}
```

The other peer keeps its calling code separate:

```java
public final class GreetingCaller {
    private final RpcEndpoint rpc;
    private final RpcCodec<JsonElement> codec;

    public GreetingCaller(RpcEndpoint rpc, RpcCodec<JsonElement> codec) {
        this.rpc = rpc;
        this.codec = codec;
    }

    public String hello(String value) {
        Greeting greeting = rpc.proxy("greeting", Greeting.class, codec);
        return greeting.hello(value);
    }
}
```

The common business contract is shared between those two peers, not installed on the relay:

```java
/** Answers greeting requests. */
public interface Greeting {
    String hello(String value);
}
```

For example, one peer enables `value -> value + " World"`, while the other calls `hello("Hello")`. Reverse-direction
services are independently registered and called through the same endpoint. No business-interface beans are required.

Keep the returned registration until the implementation should stop accepting new calls, then close that registration.
Proxies require no separate close. Shared endpoint ownership belongs to the connection service, not an individual
caller. Desktop retains endpoints and registrations across transient reconnects, but invalidates them before an
authentication identity change and on shutdown. Backend owners close missing physical connections after a successful
registry reconciliation; failed registry reads preserve existing endpoints. Applications can explicitly release a
backend scope with `rpcConnections.closeConnection(selected.connectionId())`.
Backend owners share asynchronous opening results while callers wait with a timeout. Idle reconciliation and
subscription renewal run through Spring scheduling, not owner-created executors. Desktop authorization expiry and
transport lifecycle jobs remain active even without business requests.

Replacing a closed endpoint does not transfer its old registrations. After an identity change or explicit closure,
obtain the new endpoint and register the appropriate implementations again. Backend connection owners and caches are
process-local; the existing exact-owner callback routing requirement still applies.

The inner JSON envelope uses `protocol: "dbeaver.rpc.v2"`, `type`, and `data`, plus `operation` for requests
or a fixed `error` code for failures. Application `data` can be any JSON value, including null; the outer DataDam message
remains an object and retains its existing request/reply correlation and source validation. Malformed inner envelopes
are ignored without exposing raw contents. Transport validation and RPC validation do not replace authentication.

The adapter bounds framing, snapshot copying, and outstanding sends to 128 operations without a waiting queue.
Cancellation of a returned send future does not free its slot until the underlying send actually finishes.

Interfaces declare ordinary one-argument methods returning values or `void`; applications do not implement transport
listeners. A `void` call waits for handler completion. Local serialization errors and transport failures become unchecked
`RpcException`; remote handler exceptions are represented only by safe error codes. See [Messaging](MESSAGING.md)
for supported contract shapes, ownership, concurrent codec/handler requirements, and timeout semantics.
