# Messaging: request/reply

This module supplies a transport-independent request/reply endpoint for Java 21. It has no dependency on DataDam,
HTTP, WebSocket, JSON, Spring, or Eclipse runtime services. The only non-JDK production dependency supplies nullability
annotations. Typed RPC is not part of this module's current API.

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
