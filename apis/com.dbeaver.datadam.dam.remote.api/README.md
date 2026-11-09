# Remote DAM API

The `com.dbeaver.datadam.dam.remote.api` module contains Remote DAM WebSocket contracts
and transport-independent messaging packages. A Remote DAM peer can be a Desktop application or a future Managed cluster.
Its session identifies the authenticated DAM transport scope, independently of any database connection.

Java package names are `com.dbeaver.datadam.dam.remote.api.*` and `com.dbeaver.messaging.*`.
See [Messaging](MESSAGING.md) for the neutral request/reply and typed RPC contracts.

## Session identity and handshake

The WebSocket handshake sends `DDRemoteDamHandshakeRequest` with `appDeploymentId`, `appInstanceId`, `workspaceId`,
`clientDamId`, `product`, and `version`, in that constructor order. All six fields are mandatory.
`DDRemoteDamHandshakeResponse` returns the assigned `damSessionId` and `DDRemoteDamSettings`, containing immutable
`account` and `dam` string maps. The desktop applies this snapshot before registering settings-dependent services
and registers `service.desktop.DDRemoteDamSettingsService` (`dbeaver.dam.settings`) for subsequent complete snapshots. A missing
settings field from an older broker produces empty maps; MCP remains disabled until explicitly enabled for the DAM.
Setting keys are declared in `DDRemoteDamSettingKeys`. Settings reads and writes use Account GraphQL.
Wire control types remain
`hello` and `ready`, and the WebSocket path remains `/ws`. Control messages have `type` and an object `payload`;
application messages additionally have `topic`. Application messages may use any nonblank type, including the control
type names.

`DDRemoteDamMessage` takes defensive payload snapshots.

`workspaceId` is required and must be a nonblank string of at most 256 UTF-16 units. Missing, null, blank, non-string,
and oversized values are rejected.

`clientDamId` is a required nonblank string of at most 2048 characters, using only the URL-safe Base64 alphabet
`[A-Za-z0-9_-]+`, without padding. The shared bound is `DDRemoteDamHandshakeRequest.MAX_CLIENT_DAM_ID_LENGTH`.
Missing, null, blank, non-string, oversized, and non-alphabet values are rejected; there is no default or legacy hello
variant. Desktop generates this value as unpadded URL-safe Base64 of the UTF-8 bytes of
`workspaceId + ':' + lowercase hexadecimal MAC address`. Generation belongs to Desktop; the API and server treat the
encoded identifier as opaque and do not decode or interpret it.

Stable DAM identity is the authenticated `accountId` together with `clientDamId`. Desktop reconnects and process restarts
preserve that identity while the workspace and MAC address remain the same. `appInstanceId` identifies a process lifetime
and changes when Desktop restarts. `damSessionId` identifies one WebSocket transport session and changes on reconnect.
Reconnects supersede the previous connection for the same account, client DAM identity and application instance.
Different application instances can have independent connections to the same DAM.

`DDRemoteDamSessionInfo` contains `appDeploymentId`, `appInstanceId`, `damSessionId`, and the handshake settings snapshot.
Transport identity uses only the three session identifiers; updated settings do not replace a physical session.
`DDRemoteDamSession` adds `product` and `version` for registry presentation. All fields are nonblank strings.
Concurrent sessions may share deployment and instance IDs when their account or client DAM identity differs; consumers
must use `damSessionId` to distinguish transport sessions. Deployment ID, product, and version are peer-supplied metadata, not
authenticated identity or authorization claims; changing them does not create a new persistent DAM identity.

## Persisted DAMs and the Account API

DAM lists, details, history, traffic, and display names are available through the authenticated Account GraphQL API.
These account-scoped snapshots are distinct from live transport sessions used for messaging and RPC.

The public Account operations remain:

- `connectedRemoteDams`: persistent DAM identities, including offline DAMs.
- `connectedRemoteDam(damId)`: account-scoped details and session history using the technical DAM UUID.
- `connectedRemoteDamByClientId(clientDamId)`: lookup by stable client identity within the authenticated account.
- `renameConnectedRemoteDamByClientId(clientDamId, damName)`: update only the display name, returning updated metadata
  or null for a missing or foreign DAM, without creating a record.
- `connectedRemoteDamAccountTraffic`: cumulative account traffic and per-DAM totals.
- `connectedRemoteDamTraffic(damId)`: DAM total and per-session counters, or null for a missing or foreign DAM.

Account derives the account from the authenticated user and enforces authorization. Renaming requires `REMOTE_DAM_MANAGE`.
Names are normalized, limited to 256 Unicode code points, and do not need to be unique. DAMs without a client ID
cannot be renamed. A lookup can return null until the DAM appears in the Account snapshot.

The shared models remain in this module: `DDRemoteDam`, `DDRemoteDamHistorySession`, `DDRemoteDamTraffic`,
`DDRemoteDamAccountTraffic`, and `DDRemoteDamTrafficDetails`. `DDRemoteDam` retains its nullable `damName`, shared name
validation, and constructor compatibility for unnamed records. These models do not imply a relay RPC contract.

- `damId` is the DAM's technical UUID, not `clientDamId`. `clientDamId` is null only for older records
  without that value; no replacement identity is generated for them.
- `product`, `version`, and `remoteAddress` describe the latest active session, or the latest session when all are offline.
  `remoteAddress` is the numeric IP address observed by the server's WebSocket session and is null when unavailable.
  It is not supplied by the peer handshake. The wire field names remain `product` and `version`.
- A DAM is `online` while the snapshot reports at least one active session. `damSessionId` identifies
  the latest active session while online and is null otherwise. This status does not guarantee that a send will succeed.
- `sessionCount` is the number of active sessions in the snapshot; `online` is exactly `sessionCount > 0`.
  `applications` contains unique product/version pairs as immutable `DDRemoteDam.Application` records, and `remoteAddresses`
  contains unique known IP addresses. Both immutable lists cover all stored sessions, including offline sessions.
- `firstConnectedAt` and `lastConnectedAt` describe the first and latest connection of the DAM identity.
  `lastDisconnectedAt` is nullable and includes known disconnects, supersession, or session expiry; it can
  describe a previous session while the DAM is online again. Timestamps are ISO-8601 instant strings on GraphQL and
  `Instant` values in Java.
- `DDRemoteDamHistorySession` describes one historical session. Deployment and instance IDs may be null for older records;
  no legacy values are fabricated. `connectedAt` is the connection instant. `endedAt` is null while online and otherwise
  is the effective close, supersession, or session-expiry instant. `durationSeconds` is a nonnegative whole-second duration
  from connection to the effective end, or to the snapshot time while online.
- Account traffic totals also include authenticated connections that did not register a persistent DAM.
  Traffic counters are nonnegative 64-bit byte counts, cumulative for the returned account, DAM, or session scope.
  They count WebSocket frame bytes, including control frames and frame headers, but exclude HTTP upgrade, TLS, and
  lower-layer overhead. These statistics are for monitoring, not billing.
- Account snapshots can lag transport changes and live traffic; use the ready channel for messaging rather than treating
  these snapshots as transport readiness checks.

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

Open a physical-channel endpoint after readiness. Opening while disconnected or closed fails without retaining
subscriptions. Endpoints can stay installed across reconnects, but pending calls fail at disconnection and are not replayed.
The peer installs `requests.handle(payload -> process(payload))`. Use distinct topics for independently handled services.
Each endpoint installs its private reply route before accepting calls; replies return to the originating endpoint.
Null responses represent successful completion without a value. This lower layer exposes protocol error codes without
application exception details; typed RPC adds its own diagnostics.

The bridge message type remains `datadam.requestreply.v1`. Envelopes contain `requestId`, `replyTo`, `type`, and either
`data` or `error`. `replyTo` is an endpoint UUID, not a network address. Private topics remain
`datadam.reply.v1.<SHA-256(request-topic)>.<endpoint-UUID>` and fit metadata limits for maximum-length topics.
Closing an endpoint removes both receiving routes and owned work without closing a shared channel.

`request` blocks a worker or virtual thread. Ordinary sends confirm transport sending, not peer receipt or business
completion. Physical channels notify disconnection and fail pending calls.
Timeout or interruption can leave remote outcome unknown; requests and responses are never automatically retried.

The direct-channel adapter orders messages and disconnections through one bounded receiving mailbox. Source validation
and mailbox insertion share a lifecycle lock; callbacks run outside it. A newly visible source inserts its generation
boundary before its first message. Delayed observers read current status rather than reverting to an old source.
The mailbox holds 128 callbacks without an executor. Excess frames are dropped; disconnection takes priority over a full
batch. Listener failure closes the receiving routes.
The neutral endpoint owns no executor. Blocking handlers must stay off a serialized receiver needed for their replies.

## Typed RPC

`com.dbeaver.messaging.rpc` supplies typed proxies and implementation registration independently of DataDam and WebSocket.
`DDRemoteDamRpcTransport` adapts request/reply to `MessagingRpcMessage<JsonElement>`. Application serialization is selected
through a codec, for example `new DDRemoteDamGsonRpcCodec(new Gson())`. The codec retains the application's Gson adapters
and naming policies, preserves declared generic types, and handles each argument independently. A varargs array is one
argument; JSON null is a nullable payload. Each proxy and registration can use a different compatible codec.
Proxy timeout overloads accept `Duration`, without changing other proxies or the shared endpoint; the default is
30 seconds unless endpoint options specify otherwise.

```java
try (var rpc = MessagingRpcEndpoint.open(DDRemoteDamRpcTransport.forChannel(channel), codec);
     var registration = rpc.register("dam.projects", DamProjects.class, implementation)) {
    ServerDocuments documents = rpc.proxy("server.documents", ServerDocuments.class);
    serveApplication(documents);
}
```

An optional borrowed `requestExecutor` runs admitted incoming requests; otherwise the receiving thread runs them.
Responses and disconnects never use that executor. Application/platform owners must keep blocking handlers off WebSocket delivery.
The transport owns no execution service.

The developer's topic is the actual request topic. Routes open lazily and are locally reused, with no automatic service
prefix. Registering locally does not register a remote service. Arrange the intended receiver's subscription before
calling; local registration is not service discovery.

In Desktop, obtain the endpoint through `DDRemoteDamDesktopSessionService.getInstance().getRpc()` once the application
session is ready, and pass it to ordinary objects. Endpoint access does not start login or wait for readiness.
Register implementations at runtime and retain their registration handles until they
should stop accepting calls. Proxies need no separate close. The session owner owns the shared endpoint. Transient
reconnects retain endpoints and registrations; authentication identity changes and shutdown invalidate them. Replacing a
closed endpoint does not transfer registrations.

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
