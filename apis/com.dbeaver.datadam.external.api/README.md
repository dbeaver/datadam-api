# External DAM API

This module contains the Desktop WebSocket contracts and a backend-only client for the External DAM internal GraphQL API.
Neither the models nor the WebSocket protocol depend on the HTTP client, Spring, or Eclipse runtime services.

## Internal GraphQL contract

The backend client posts JSON requests to `/internal/graphql` on the configured External DAM service root URL.
The service implementation must expose the following contract; adding the client alone does not deploy the endpoint.

```graphql
scalar JSON

type Query {
    externalDamConnections(accountId: ID!): [ExternalDamConnection!]!
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
}
```

- `accountId` is a UUID and is mandatory for connection queries, sends, and subscription registration.
  Connection lookup and sending must use the account and connection ID together.
- Connection queries return an immutable client-side snapshot of currently ready sockets, not persisted DAM installations or history.
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

The secret must contain 32–512 printable, non-space ASCII characters. It is sent only in the `X-DD-Service-Token` header,
never in the URL or GraphQL variables. Redirects are disabled to avoid forwarding credentials to another endpoint.
Connection establishment uses the shared REST client's 5-second default connect timeout; responses have a 10-second deadline.
HTTP error bodies, GraphQL error details, and malformed response contents are not copied into client exceptions.

The service secret authenticates a trusted backend, not an end user. A backend exposing these operations to users must
derive `accountId` from its authorized account context rather than trusting an arbitrary account ID supplied by the caller.
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

## Backend callback subscriptions

`registerSubscription(subscriberId, subscriptionId, accountId, connectionId, topic)` returns the lease in seconds.
The receiving process generates the UUID `subscriptionId`; repeated registration with the same scope renews it.
A null `connectionId` subscribes to the account's matching messages, including future Desktop connections.
A non-null connection ID filters one physical connection and does not follow a replacement socket.
The subscriber ID selects a trusted destination configured on External DAM; registration never supplies a callback URL.

`DDExternalDamCallbackClient` delivers messages to the receiver's separately authenticated
`POST /internal/external-dam/graphql` using its independent callback secret. Successful acknowledgement extends the
lease. The acknowledgement does not describe local subscriber presence or business processing. Local cancellation
uses `unregisterSubscription`, including cleanup when the receiver no longer recognizes an incoming registration.
External DAM discards queued work on cancellation or expiry; an already claimed callback may finish.
Messages are not retried or replayed after callback failure. Callback queues and workers have configured bounds.

Spring services use the shared `com.dbeaver.datadam.external.base` module from the DataDam server repository.
It supplies the callback endpoint, local dispatch, `DDSubscription.close()` cancellation, and automatic renewal
during idle periods. Select a scoped channel once with `subscriptions.forConnection(accountId, connectionId)` or
`subscriptions.forAccount(accountId)`, then call `channel.subscribe(topic, listener)` without repeating identifiers.
The low-level registration protocol retains account and connection scope to enforce account ownership.
Application handlers do not renew registrations themselves. Every callback destination must reach the exact
receiving process that owns the local registration.

## Channel state

`DDMessageChannel.getStatus()` returns an immutable `DDChannelStatus`: `DISCONNECTED`, `CONNECTING`, `CONNECTED`,
or `CLOSED`. Only `CONNECTED` contains connection information, and `status.isReady()` checks that state.
`observeState(observer)` delivers the current state and subsequent changes in order without a snapshot/subscription
race. Closing the returned subscription cancels only that observer. Initial notification can run synchronously;
subsequent callbacks run on the notifying thread. Existing `addConnectionListener` remains transitions-only.

## Request/reply

The independent `com.dbeaver.messaging` module supplies `RequestReplyEndpoint<P>`. It knows nothing about DataDam
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
ordinary channel sends and callback GraphQL acknowledgements retain their existing transport-only meaning. The backend
adapter uses bounded asynchronous sending around its blocking HTTP client. It keeps the existing in-memory callback
subscriptions and lease renewal, and exposes request/reply only for `forConnection`, with no implicit connection
selection from account-wide subscriptions.

Closing the request/reply endpoint cancels its receiving subscriptions and owned work, without closing the shared
channel or subscription facade. Closing the subscription facade rejects new backend sends and prevents queued sends
from starting; an already claimed HTTP send can finish. Physical channels report disconnects immediately; backend
callbacks have no physical connection-state feed, so pending calls use their deadlines when the peer disappears.
Timeout or interruption can leave the remote outcome unknown. No request or response is automatically retried.

## Typed RPC

`com.dbeaver.messaging.rpc` adds typed Java proxies and registered implementations without depending on DataDam or
WebSocket. `DDRpcTransport` adapts the existing request/reply transport to the neutral `RpcMessage<JsonElement>` envelope.
It contains only protocol framing; application serialization is selected by the developer through `RpcCodec<JsonElement>`.

For example, an application can choose Gson once for an endpoint:

```java
Gson gson = new Gson();
RpcCodec<JsonElement> codec = new RpcCodec<>() {
    @Override
    public JsonElement encode(Object value, Type type) {
        return gson.toJsonTree(value, type);
    }

    @Override
    public Object decode(JsonElement payload, Type type) {
        return gson.fromJson(payload, type);
    }
};
```

The Desktop side installs an implementation and can independently obtain proxies for backend services:

```java
var transport = DDRpcTransport.forChannel(channel, "rpc.account");
try (var rpc = RpcEndpoint.open(transport, codec);
     var registration = rpc.register("desktop.projects", DesktopProjects.class, implementation)) {
    ServerDocuments documents = rpc.proxy("server.documents", ServerDocuments.class);
    serveApplication(documents);
}
```

The backend selects its physical account/connection scope once and uses the same endpoint API:

```java
var scope = subscriptions.forConnection(accountId, connectionId);
var transport = new DDRpcTransport(scope.requestReply("rpc.account"));
try (var rpc = RpcEndpoint.open(transport, codec);
     var registration = rpc.register("server.documents", ServerDocuments.class, implementation)) {
    DesktopProjects projects = rpc.proxy("desktop.projects", DesktopProjects.class);
    serveApplication(projects);
}
```

Use the same underlying request/reply topic (`"rpc.account"` here) at both ends of the selected channel. Logical service topics
such as `desktop.projects` and `server.documents` are multiplexed inside that lane; they do not open connections or add
per-service transport subscriptions. Registering a local service never implicitly registers a service on the other peer.
The existing private reply topic continues to route each answer back to the exact initiating process.

Each lane has one receiving RPC endpoint at each peer. If Account, Storage, or other backend processes share one Desktop
connection, use separate lanes such as `rpc.account` and `rpc.storage`, with a corresponding Desktop endpoint for each.
Sharing a lane between multiple backend receivers is not remote service discovery: unrelated receivers could answer
`UNKNOWN_SERVICE` before the intended service. Replica routing and callback ownership retain the existing process-local
subscription constraints.

The inner JSON envelope uses `protocol: "dbeaver.rpc.v1"`, `type`, and `data`, plus `topic` and `operation` for requests
or a fixed `error` code for failures. Application `data` can be any JSON value, including null; the outer DataDam message
remains an object and retains its existing request/reply correlation and source validation. Malformed inner envelopes
are ignored without exposing raw contents. Transport validation and RPC validation do not replace authentication.

The adapter bounds framing, snapshot copying, and outstanding sends to 128 operations without a waiting queue.
Cancellation of a returned send future does not free its slot until the underlying send actually finishes.

Interfaces declare ordinary one-argument methods returning values or `void`; applications do not implement transport
listeners. A `void` call waits for handler completion. Local serialization errors and transport failures become unchecked
`RpcException`; remote handler exceptions are represented only by safe error codes. See the messaging module's README
for supported contract shapes, ownership, concurrent codec/handler requirements, and timeout semantics.
