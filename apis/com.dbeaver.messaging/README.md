# Messaging: request/reply and typed RPC

This module supplies a transport-independent request/reply endpoint for Java 21. It has no dependency on DataDam,
HTTP, WebSocket, JSON, Spring, or Eclipse runtime services. The only non-JDK production dependency supplies nullability
annotations. The `rpc` package adds ordinary Java interface calls over the same request/reply mechanism.

## Usage

Create an endpoint over a transport already scoped to the intended peer and logical request topic:

```java
try (var requests = RequestReplyEndpoint.open(transport)) {
    var response = requests.request(payload);
}
```

The receiving peer installs one handler on its own endpoint:

```java
try (var requests = RequestReplyEndpoint.open(transport);
     var registration = requests.handle(request -> process(request))) {
    serveApplication();
}
```

An endpoint can initiate and handle requests concurrently. `request` blocks the calling thread and returns the response
payload, or null for successful completion without a value. Use a worker or virtual thread. Incoming handlers run on
the endpoint's virtual threads, outside the transport listener. Future objects are internal.

`RequestReplyEndpoint<P>` leaves the payload representation to the application and transport adapter. For example,
`P` can be a string, an immutable application value, or an encoded JSON object. The core does not copy arbitrary `P`
values: payloads must remain immutable while a request is in use, or the adapter must take defensive snapshots.

The default `RequestReplyOptions` use a 30-second deadline, at most 256 outgoing requests, and 64 concurrent incoming
handlers. Supply options to `open`, or a deadline to an individual `request(payload, timeout)` call. Deadlines are
measured with monotonic time and include local sending. The handler limit has no waiting queue: saturation replies
with `OVERLOADED`. Outgoing saturation fails locally without sending.
The handler slot covers application-handler execution and is released before response sending. Each transport adapter
separately bounds its encoding and outstanding send work.

## Completion and failure

Pending correlation is installed before sending, including when the adapter delivers an immediate response before
its send stage completes. Request IDs and reply endpoint IDs are independent UUIDs. A response must match both.
Unknown, late, and duplicate responses do not complete another request.

`RequestReplyException.reason()` distinguishes local closure, disconnection, capacity rejection, send failure, timeout,
and remote errors. `remoteError()` identifies `NO_HANDLER`, `OVERLOADED`, or `HANDLER_FAILED` for remote errors. Handler
exception classes, messages, and stack traces are not sent to the peer. Local send failures retain their causes.

Interruption propagates as `InterruptedException` and releases the pending slot. A thread already interrupted when
calling `request` does not send. Timeout, interruption, or a send error can leave the remote outcome unknown: none
promises remote cancellation or makes a non-idempotent request safe to repeat. The library never retries requests,
replays results, or provides exactly-once execution. Duplicate requests still being handled are ignored; completed
request IDs are not retained as a replay history.

Closing a handler registration removes only that handler; already accepted work can finish. The returned registration
is identity-scoped, so closing it again cannot remove a replacement handler. Closing the endpoint fails pending calls,
removes its transport subscription, and interrupts accepted work. It does not close the borrowed physical transport.
Closing is idempotent and does not wait for application code that ignores interruption.

A disconnect notification fails pending calls and invalidates replies from work accepted before that notification.
Handlers are interrupted, but continue counting against capacity until their execution actually stops. The receiving
subscription can stay installed across reconnects. New calls use the adapter's normal send readiness checks. If an
adapter cannot observe peer disconnection, outstanding calls expire through their deadlines instead.

## Transport adapter contract

Implement `RequestReplyTransport<P>` to integrate a message transport:

- `subscribe(endpointId, listener)` establishes receiving routes before returning. Requests go to one designated
  handler endpoint; responses go to the original request's `replyTo` endpoint. A transport may limit an adapter
  instance to one endpoint subscription.
- Failed subscription establishment must roll back every partially installed route and owned resource.
- `send(message)` is nonblocking and returns a bounded transport send stage. Completing that stage confirms sending,
  not remote business completion. A response can arrive before this stage completes.
- The adapter retains peer scope, verifies the source of received frames, and uses `replyTo` only as a logical reply
  destination. Correlation UUIDs do not replace authentication or account/peer authorization.
- Disconnection is reported through `listener.onDisconnect()`. Incoming callbacks can be concurrent and can occur
  synchronously during subscription establishment or sending.
- Closing the returned `MessageSubscription` removes receiving routes and releases adapter-owned resources. It must
  leave a caller-owned physical connection available to other consumers.
- The adapter owns wire validation, payload snapshots, message size limits, and bounded transport send work. It must
  not introduce automatic retries or an offline business-message queue.

Transport write failure or a lost reply can prevent a caller from seeing a completed remote operation. Response send
failure is not retried. An already claimed transport send can finish while closure or disconnection is taking place.

## Typed RPC

Both peers use the same classes. A local service registration and a proxy for a remote service are independent:
neither requires a service in the reverse direction, and either peer can initiate calls after transport establishment.
There is no client/server distinction in the RPC API.

The application shares an interface between peers and supplies its implementation on the receiving side:

```java
public interface Documents {
    Document load(DocumentRequest request);
    void save(SaveRequest request);
}
```

The connection owner creates one reusable endpoint over a topic-scoped transport factory. The receiver supplies
its own codec when registering an implementation:

```java
try (var rpc = RpcEndpoint.open(transports);
     var registration = rpc.register("documents", Documents.class, implementation, codec)) {
    serveApplication();
}
```

The caller creates its own endpoint over the corresponding transport and obtains a proxy:

```java
try (var rpc = RpcEndpoint.open(transports)) {
    Documents documents = rpc.proxy("documents", Documents.class, codec);
    Document document = documents.load(request);
    documents.save(saveRequest);
}
```

An owner can provide a default codec with `RpcEndpoint.open(transports, defaultCodec)`. Its consumers can then use
`rpc.proxy("documents", Documents.class)` and `rpc.register("documents", Documents.class, implementation)`.
The overloads with an explicit codec override that fallback only for the new proxy or registration. The core does
not construct a serialization framework. Without a configured default, no-codec overloads fail before opening routes.

The same endpoint can register other local services or obtain proxies for services on the opposite peer. The supplied
topic is the actual transport topic, not a service key inside another lane. The factory opens a request/reply transport
for each topic on demand; repeated use shares its local topic scope. A topic has at most one local implementation,
and different topics can use the same interface with different implementations and codecs. Topic naming and recipient
subscriptions belong to the application. Proxy construction establishes local receiving routes but does not contact
the remote implementation or prove service presence.
Opening a topic can establish subscriptions synchronously; use a worker thread when the adapter performs network I/O.

Proxy methods block the calling thread. Call them from worker or virtual threads, not a transport listener or event loop.

`RpcEndpoint` has no payload type parameter. Its transport factory supplies topic-scoped
`RequestReplyTransport<RpcMessage<P>>` adapters. Each proxy and registration supplies a codec compatible with that
payload representation. Business code receives an ordinary `RpcEndpoint` and uses typed interfaces. The transport
adapter encodes the neutral protocol envelope; the local codec encodes application arguments and results. A TCP, WebSocket,
long-polling, or in-memory transport can implement this interface without changing RPC code. No WebSocket types or
JSON library are used here.

### Application-owned registrations and callers

Obtain an endpoint once from the application's connection owner, then pass it to ordinary Java objects. Registration
and proxy creation are runtime operations and do not require Spring or a separate bean for each business interface.

```java
public final class DocumentReceiver {
    private final RpcEndpoint rpc;
    private final RpcCodec<?> documentCodec;

    public DocumentReceiver(RpcEndpoint rpc, RpcCodec<?> documentCodec) {
        this.rpc = rpc;
        this.documentCodec = documentCodec;
    }

    public MessageSubscription enable(Documents implementation) {
        return rpc.register("documents", Documents.class, implementation, documentCodec);
    }
}
```

The opposite peer uses its own endpoint in a separate caller:

```java
public final class DocumentCaller {
    private final RpcEndpoint rpc;
    private final RpcCodec<?> documentCodec;

    public DocumentCaller(RpcEndpoint rpc, RpcCodec<?> documentCodec) {
        this.rpc = rpc;
        this.documentCodec = documentCodec;
    }

    public Document load(DocumentRequest request) {
        Documents documents = rpc.proxy("documents", Documents.class, documentCodec);
        return documents.load(request);
    }
}
```

Keep the receiving `MessageSubscription` while the implementation is available, then close it to unregister. A proxy
is an ordinary business interface, not a registration handle, and requires no separate close. Application code sharing
an endpoint closes its own registrations; the connection owner closes the endpoint when its scope ends. Owners can
use `isClosed()` to reject new use once closure starts, and `isCloseComplete()` before releasing a cached endpoint's
scope reservation. The latter becomes true after handler and receiving-subscription cleanup finishes, including when
cleanup throws. A repeated `close()` does not wait for another thread's ongoing cleanup.

### Contracts and codecs

- Contracts are public, nonsealed interfaces with at least one abstract operation and exactly one argument per method.
  Return values can be ordinary reference or primitive types, or `void`. Reference arguments and results can be null.
- Inherited abstract operations and overloads are supported. Operation IDs combine the method name and its erased
  argument type; only methods from the registered interface can be invoked. Incoming names never trigger class loading
  or arbitrary reflective lookup on the implementation.
- Concrete generic argument and result types, such as `List<Document>`, reach the codec as `Type` values. Unresolved
  type variables, generic methods, default methods, varargs, and `Future`/`CompletionStage` returns are rejected during
  proxy construction or registration. Generic-parent substitutions are not inferred. Inherited operations with
  ambiguous generic argument or result types must be explicitly redeclared in the public contract.
- `RpcCodec.encode(value, type)` and `decode(payload, type)` are selected per proxy or implementation registration. Implementations must handle
  nulls and concurrent calls. Serialization remains application-owned; the RPC core does not construct application
  types or select a serialization framework. Payload immutability and snapshot requirements are the same as request/reply.
  Peers must use compatible contracts and codecs; no automatic contract negotiation or class transfer is performed.
- Implementations may receive concurrent calls and must manage their own application state safely. Incoming execution
  uses the existing bounded request/reply handlers; the RPC layer adds no executor or unbounded message queue.
- `equals`, `hashCode`, and `toString` on a proxy are local identity operations and do not send requests or call codecs.

### Failure and ownership

`void` still waits for remote handler completion and a successful reply. It is not fire-and-forget, and its response
does not invoke the application codec. `RpcException` is unchecked, so a business interface does not need a library
exception declaration. Its reason distinguishes timeout, interruption, disconnection, overload, closure, send failure,
codec failure, invalid response, and remote failure. Caller interruption restores the interrupt flag.

An absent logical service returns `UNKNOWN_SERVICE`; an absent operation returns `UNKNOWN_OPERATION`. Invalid arguments,
handler failures, and remote codec failures return fixed error codes, never exception types, messages, or stack traces.
Declared application exceptions are not reconstructed on the caller. A peer with no receiving endpoint can only be
detected through transport failure or timeout. Local failures retain their causes.

`RpcEndpoint.open(transports, options)` accepts the existing `RequestReplyOptions` for deadlines and capacity.
The deadline covers the underlying request/reply call, not arbitrary application codec execution. Codecs should complete
promptly. Timeout or interruption leaves the remote outcome potentially unknown; the RPC layer adds no retries or
remote cancellation promises.

Closing a service registration removes only that local implementation. It does not close the endpoint or its proxies,
and work already dispatched to the implementation can finish. Repeatedly closing an old registration cannot remove a
replacement. Closing the RPC endpoint invalidates its proxies, clears service registrations, and closes its underlying
request/reply scopes, failing pending calls. The borrowed physical transport remains open.
