/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp
 *
 * All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of DBeaver Corp and its suppliers, if any.
 * The intellectual and technical concepts contained
 * herein are proprietary to DBeaver Corp and its suppliers
 * and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from DBeaver Corp.
 */
package com.dbeaver.messaging.requestreply;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class RequestReplyEndpointTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final List<Thread> callers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopCallers() throws InterruptedException {
        callers.forEach(Thread::interrupt);
        for (Thread caller : callers) {
            join(caller);
        }
    }

    @Test
    void subscribesBeforeReturningAndAcceptsAReplyBeforeSendCompletes() throws Exception {
        var transport = new MemoryTransport();
        var acknowledgement = new CompletableFuture<Void>();
        try (var endpoint = RequestReplyEndpoint.open(transport)) {
            assertEquals(1, transport.listeners.size());
            transport.sender = message -> {
                assertTrue(transport.listeners.containsKey(message.replyTo()));
                transport.deliver(message.response("immediate reply"));
                return acknowledgement;
            };

            assertEquals("immediate reply", endpoint.request("request"));
            assertFalse(acknowledgement.isDone());
            assertEquals(RequestReplyMessage.Type.REQUEST, transport.next().type());
            assertTrue(transport.sent.isEmpty());

            assertTrue(acknowledgement.completeExceptionally(new IllegalStateException("late send failure")));
            assertEquals("immediate reply", endpoint.request("another request"));
            assertEquals(1, transport.sent.size());
        }
        assertEquals(1, transport.subscriptionCloses.get());
    }

    @Test
    void failsOnAnAsynchronousSendErrorBeforeTheReplyAndReleasesCapacity() throws Exception {
        var transport = new MemoryTransport();
        var acknowledgement = new CompletableFuture<Void>();
        var cause = new IllegalStateException("send failure");
        transport.sender = message -> acknowledgement;
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
            Future<String> pending = async(() -> endpoint.request("request"));
            RequestReplyMessage<String> request = transport.next();

            acknowledgement.completeExceptionally(cause);

            assertSame(cause, failure(RequestReplyException.Reason.SEND_FAILED, pending).getCause());
            transport.deliver(request.response("too late"));
            transport.deliver(request.failure(RequestReplyMessage.Error.HANDLER_FAILED));
            assertTrue(transport.sent.isEmpty());

            transport.sender = message -> {
                transport.deliver(message.response("recovered"));
                return CompletableFuture.completedFuture(null);
            };
            assertEquals("recovered", endpoint.request("next request"));
            assertEquals(1, transport.sent.size());
        }
    }

    @Test
    void convertsSynchronousSendFailuresWithoutRetryingOrLeakingCapacity() throws Exception {
        var transport = new MemoryTransport();
        var cause = new IllegalStateException("synchronous send failure");
        transport.sender = message -> {
            throw cause;
        };
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
            assertSame(cause, failure(RequestReplyException.Reason.SEND_FAILED, () -> endpoint.request("request")).getCause());
            assertEquals("request", transport.next().payload());
            assertTrue(transport.sent.isEmpty());

            transport.sender = message -> {
                transport.deliver(message.response("recovered"));
                return CompletableFuture.completedFuture(null);
            };
            assertEquals("recovered", endpoint.request("next request"));
            assertEquals(1, transport.sent.size());
        }
    }

    @Test
    void preservesTypedAsynchronousSendFailuresAndReleasesCapacityBeforeLateNotifications() throws Exception {
        for (RequestReplyException expected : List.of(
            new RequestReplyException(RequestReplyException.Reason.OVERLOADED),
            new RequestReplyException(RequestReplyException.Reason.DISCONNECTED),
            new RequestReplyException(RequestReplyException.Reason.CLOSED),
            new RequestReplyException(RequestReplyException.Reason.TIMEOUT),
            new RequestReplyException(RequestReplyException.Reason.SEND_FAILED),
            new RequestReplyException(RequestReplyMessage.Error.OVERLOADED))) {
            for (Throwable error : List.of(expected, new CompletionException(expected), new ExecutionException(expected),
                new CompletionException(new ExecutionException(new CompletionException(expected))))) {
                var transport = new MemoryTransport();
                var acknowledgement = new CompletableFuture<Void>();
                transport.sender = message -> acknowledgement;
                try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
                    Future<String> pending = async(() -> endpoint.request("request"));
                    final RequestReplyMessage<String> request = transport.next();

                    acknowledgement.completeExceptionally(error);

                    assertSame(expected, failure(expected.reason(), pending));
                    transport.disconnect();
                    transport.deliver(request.response("too late"));
                    transport.sender = message -> {
                        transport.deliver(message.response("recovered"));
                        return CompletableFuture.completedFuture(null);
                    };
                    assertEquals("recovered", endpoint.request("next request"));
                    assertEquals("next request", transport.next().payload());
                    assertTrue(transport.sent.isEmpty());
                }
            }
        }
    }

    @Test
    void preservesTypedSynchronousSendFailuresWrappedByCompletionStages() throws Exception {
        for (RequestReplyException expected : List.of(
            new RequestReplyException(RequestReplyException.Reason.OVERLOADED),
            new RequestReplyException(RequestReplyException.Reason.DISCONNECTED),
            new RequestReplyException(RequestReplyException.Reason.CLOSED),
            new RequestReplyException(RequestReplyMessage.Error.HANDLER_FAILED))) {
            for (CompletionException error : List.of(new CompletionException(expected),
                new CompletionException(new ExecutionException(expected)))) {
                var transport = new MemoryTransport();
                transport.sender = message -> {
                    throw error;
                };
                try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
                    assertSame(expected, failure(expected.reason(), () -> endpoint.request("first")));
                    assertSame(expected, failure(expected.reason(), () -> endpoint.request("second")));
                    assertEquals(2, transport.sent.size());
                }
            }
        }
    }

    @Test
    void keepsUntypedAndUnrelatedWrappedSendErrorsAsSendFailures() throws Exception {
        var cause = new IllegalStateException("untyped send failure");
        for (RuntimeException error : List.of(cause, new CompletionException(cause),
            new CompletionException(new ExecutionException(cause)), new CompletionException((Throwable) null),
            new IllegalStateException(new RequestReplyException(RequestReplyException.Reason.DISCONNECTED)))) {
            for (boolean asynchronous : List.of(false, true)) {
                var transport = new MemoryTransport();
                transport.sender = message -> {
                    if (asynchronous) {
                        return CompletableFuture.failedFuture(error);
                    }
                    throw error;
                };
                try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
                    assertSame(error, failure(RequestReplyException.Reason.SEND_FAILED, () -> endpoint.request("request")).getCause());
                    assertEquals(1, transport.sent.size());
                }
            }
        }
    }

    @Test
    void correlatesConcurrentResponsesDeliveredInReverseOrder() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(8, 1))) {
            Map<String, Future<String>> results = new HashMap<>();
            for (int index = 0; index < 8; index++) {
                String payload = "request-" + index;
                results.put(payload, async(() -> endpoint.request(payload)));
            }
            List<RequestReplyMessage<String>> requests = new ArrayList<>();
            Set<UUID> requestIds = new HashSet<>();
            Set<UUID> replyTargets = new HashSet<>();
            for (int index = 0; index < results.size(); index++) {
                RequestReplyMessage<String> request = transport.next();
                requests.add(request);
                assertEquals(RequestReplyMessage.Type.REQUEST, request.type());
                assertTrue(requestIds.add(request.requestId()));
                replyTargets.add(request.replyTo());
            }
            assertEquals(1, replyTargets.size());
            for (int index = requests.size() - 1; index >= 0; index--) {
                RequestReplyMessage<String> request = requests.get(index);
                transport.deliver(request.response("reply:" + request.payload()));
            }

            for (var entry : results.entrySet()) {
                assertEquals("reply:" + entry.getKey(), result(entry.getValue()));
            }
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void invokesTheHandlerInlineOnTheReceivingThreadAndReturnsANullResult() throws Exception {
        var transport = new MemoryTransport();
        var handlerThread = new AtomicReference<Thread>();
        var handled = new AtomicReference<String>();
        try (var endpoint = RequestReplyEndpoint.open(transport);
             var registration = endpoint.handle(payload -> {
                 handlerThread.set(Thread.currentThread());
                 handled.set(payload);
                 return null;
             })) {
            var request = request("nullable result");
            transport.deliver(request);

            assertEquals("nullable result", handled.get());
            assertSame(Thread.currentThread(), handlerThread.get());
            assertEquals(request.response(null), transport.next());
        }
    }

    @Test
    void reportsMissingAndFailingHandlersWithoutExposingHandlerDetails() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        connect(clientTransport, serverTransport);
        try (var client = RequestReplyEndpoint.open(clientTransport);
             var server = RequestReplyEndpoint.open(serverTransport)) {
            RequestReplyException missing = failure(RequestReplyException.Reason.REMOTE_ERROR, () -> client.request("missing"));
            assertEquals(RequestReplyMessage.Error.NO_HANDLER, missing.remoteError());
            assertNull(missing.getCause());
            assertEquals(clientTransport.next().failure(RequestReplyMessage.Error.NO_HANDLER), serverTransport.next());

            try (var registration = server.handle(payload -> {
                throw new IllegalStateException("private handler details: " + payload);
            })) {
                RequestReplyException failed = failure(RequestReplyException.Reason.REMOTE_ERROR, () -> client.request("secret"));

                assertEquals(RequestReplyMessage.Error.HANDLER_FAILED, failed.remoteError());
                assertNull(failed.getCause());
                assertFalse(failed.toString().contains("private"));
                assertFalse(failed.toString().contains("secret"));
                assertEquals(clientTransport.next().failure(RequestReplyMessage.Error.HANDLER_FAILED), serverTransport.next());
            }
        }
    }

    @Test
    void releasesHandlerCapacityWhenReplySendingFails() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1));
             var registration = endpoint.handle(payload -> "reply:" + payload)) {
            for (boolean asynchronous : List.of(false, true)) {
                transport.sender = message -> {
                    var failure = new IllegalStateException("reply send failure");
                    if (asynchronous) {
                        return CompletableFuture.failedFuture(failure);
                    }
                    throw failure;
                };
                var request = request("request");

                assertDoesNotThrow(() -> transport.deliver(request));
                assertEquals(request.response("reply:request"), transport.next());
            }
            transport.sender = message -> CompletableFuture.completedFuture(null);
            var request = request("recovered");
            transport.deliver(request);
            assertEquals(request.response("reply:recovered"), transport.next());
        }
    }

    @Test
    void mapsTransportExecutionRejectionToOverloadAndReleasesAdmission() throws Exception {
        var transport = new MemoryTransport();
        var rejected = new AtomicReference<Runnable>();
        var calls = new AtomicInteger();
        transport.requestExecutor = request -> {
            rejected.set(request);
            throw new RejectedExecutionException("Request execution unavailable");
        };
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1));
             var registration = endpoint.handle(payload -> {
                 calls.incrementAndGet();
                 return "reply:" + payload;
             })) {
            var request = request("rejected");
            assertDoesNotThrow(() -> transport.deliver(request));
            assertEquals(request.failure(RequestReplyMessage.Error.OVERLOADED), transport.next());
            assertEquals(0, calls.get());
            rejected.get().run();
            assertEquals(0, calls.get());
            assertTrue(transport.sent.isEmpty());

            transport.requestExecutor = null;
            transport.deliver(request);
            assertEquals(request.response("reply:rejected"), transport.next());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void countsQueuedRequestsAndCancelsThemBeforeExecutionOnDisconnectOrClose() throws Exception {
        for (boolean closing : List.of(false, true)) {
            var transport = new MemoryTransport();
            var queued = new LinkedBlockingQueue<Runnable>();
            var calls = new AtomicInteger();
            transport.requestExecutor = queued::add;
            try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1));
                 var registration = endpoint.handle(payload -> {
                     calls.incrementAndGet();
                     return "reply:" + payload;
                 })) {
                var first = request("queued");
                transport.deliver(first);
                transport.deliver(first);
                assertEquals(1, queued.size());
                var excess = request("excess");
                transport.deliver(excess);
                assertEquals(excess.failure(RequestReplyMessage.Error.OVERLOADED), transport.next());
                assertEquals(0, calls.get());

                if (closing) {
                    endpoint.close();
                } else {
                    transport.disconnect();
                }
                queued.remove().run();
                assertEquals(0, calls.get());
                assertTrue(transport.sent.isEmpty());

                if (!closing) {
                    var fresh = request("fresh");
                    transport.deliver(fresh);
                    queued.remove().run();
                    assertEquals(fresh.response("reply:fresh"), transport.next());
                    assertEquals(1, calls.get());
                }
            }
        }
    }

    @Test
    void isolatesReplyTargetsAndIgnoresUnknownAndDuplicateResponses() throws Exception {
        var transport = new MemoryTransport();
        try (var first = RequestReplyEndpoint.open(transport);
             var second = RequestReplyEndpoint.open(transport)) {
            final Future<String> firstResult = async(() -> first.request("first"));
            RequestReplyMessage<String> firstRequest = transport.next();
            final Future<String> secondResult = async(() -> second.request("second"));
            RequestReplyMessage<String> secondRequest = transport.next();
            assertNotEquals(firstRequest.replyTo(), secondRequest.replyTo());

            transport.deliver(new RequestReplyMessage<>(firstRequest.requestId(), secondRequest.replyTo(),
                RequestReplyMessage.Type.RESPONSE, "wrong endpoint", null));
            transport.deliver(new RequestReplyMessage<>(secondRequest.requestId(), firstRequest.replyTo(),
                RequestReplyMessage.Type.ERROR, null, RequestReplyMessage.Error.HANDLER_FAILED));
            var unknown = RequestReplyMessage.request(UUID.randomUUID(), firstRequest.replyTo(), "unknown");
            transport.deliver(unknown.response("unknown response"));
            transport.deliver(unknown.failure(RequestReplyMessage.Error.NO_HANDLER));
            assertFalse(firstResult.isDone());
            assertFalse(secondResult.isDone());

            transport.deliver(firstRequest.response("first reply"));
            transport.deliver(firstRequest.response("duplicate"));
            transport.deliver(firstRequest.failure(RequestReplyMessage.Error.HANDLER_FAILED));
            assertEquals("first reply", result(firstResult));
            assertFalse(secondResult.isDone());
            transport.deliver(secondRequest.response("second reply"));
            assertEquals("second reply", result(secondResult));
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void usesTheDefaultTimeoutAndReleasesCapacityBeforeIgnoringLateReplies() throws Exception {
        var transport = new MemoryTransport();
        var options = new RequestReplyOptions(Duration.ofMillis(100), 1, 1);
        try (var endpoint = RequestReplyEndpoint.open(transport, options)) {
            failure(RequestReplyException.Reason.TIMEOUT, () -> endpoint.request("timed out"));
            RequestReplyMessage<String> expired = transport.next();
            assertTrue(transport.sent.isEmpty());

            Future<String> next = async(() -> endpoint.request("next", WAIT));
            final RequestReplyMessage<String> request = transport.next();
            transport.deliver(expired.response("late response"));
            transport.deliver(expired.failure(RequestReplyMessage.Error.HANDLER_FAILED));
            assertFalse(next.isDone());
            transport.deliver(request.response("next reply"));

            assertEquals("next reply", result(next));
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void timesOutWhileAnIndependentReceivingThreadIsStillHandlingTheRequest() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var handlerThread = new AtomicReference<Thread>();
        connect(clientTransport, serverTransport);
        try (var client = RequestReplyEndpoint.open(clientTransport, options(1, 1));
             var server = RequestReplyEndpoint.open(serverTransport, options(1, 1));
             var registration = server.handle(payload -> {
                 handlerThread.set(Thread.currentThread());
                 entered.countDown();
                 await(release);
                 return "reply:" + payload;
             })) {
            try {
                Future<String> expired = async(() -> client.request("expired", Duration.ofMillis(100)));
                await(entered);
                failure(RequestReplyException.Reason.TIMEOUT, expired);
                assertTrue(handlerThread.get().isAlive());
                release.countDown();
                join(handlerThread.get());
                assertEquals(clientTransport.next().response("reply:expired"), serverTransport.next());
                assertEquals("reply:fresh", client.request("fresh"));
                assertEquals(clientTransport.next().response("reply:fresh"), serverTransport.next());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsRepliesAtTheDeadlineEvenWhenTheyArriveBeforeSendReturns() throws Exception {
        var transport = new MemoryTransport();
        var clock = new AtomicLong();
        try (var endpoint = new RequestReplyEndpoint<>(transport, options(1, 1), clock::get)) {
            for (boolean error : List.of(false, true)) {
                transport.sender = message -> {
                    clock.addAndGet(WAIT.toNanos());
                    transport.deliver(error ? message.failure(RequestReplyMessage.Error.HANDLER_FAILED) : message.response("late"));
                    return CompletableFuture.completedFuture(null);
                };

                failure(RequestReplyException.Reason.TIMEOUT, () -> endpoint.request("expired"));

                assertEquals("expired", transport.next().payload());
                assertTrue(transport.sent.isEmpty());
            }
            transport.sender = message -> {
                transport.deliver(message.response("on time"));
                return CompletableFuture.completedFuture(null);
            };
            assertEquals("on time", endpoint.request("fresh"));
        }
    }

    @Test
    void doesNotSendOrConsumeCapacityWhenTheCallerIsAlreadyInterrupted() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
            Future<String> pending = async(() -> {
                Thread.currentThread().interrupt();
                return endpoint.request("already interrupted");
            });

            ExecutionException failure = assertThrows(ExecutionException.class, () -> result(pending));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(transport.sent.isEmpty());
            transport.sender = message -> {
                transport.deliver(message.response("next reply"));
                return CompletableFuture.completedFuture(null);
            };
            assertEquals("next reply", endpoint.request("next"));
        }
    }

    @Test
    void propagatesInterruptionAndReleasesThePendingSlotWithoutSendingCancellation() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
            var pending = new FutureTask<>(() -> endpoint.request("interrupted"));
            Thread caller = start(pending);
            final RequestReplyMessage<String> interrupted = transport.next();

            caller.interrupt();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> result(pending));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            join(caller);
            assertTrue(transport.sent.isEmpty());
            Future<String> next = async(() -> endpoint.request("next"));
            RequestReplyMessage<String> request = transport.next();
            transport.deliver(interrupted.response("too late"));
            assertFalse(next.isDone());
            transport.deliver(request.response("next reply"));
            assertEquals("next reply", result(next));
        }
    }

    @Test
    void rejectsExcessPendingRequestsBeforeSendingAndReusesReleasedSlots() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(2, 1))) {
            final Future<String> first = async(() -> endpoint.request("first"));
            final RequestReplyMessage<String> firstRequest = transport.next();
            final Future<String> second = async(() -> endpoint.request("second"));
            RequestReplyMessage<String> secondRequest = transport.next();

            failure(RequestReplyException.Reason.OVERLOADED, () -> endpoint.request("excess"));
            assertTrue(transport.sent.isEmpty());
            transport.deliver(secondRequest.response("second reply"));
            assertEquals("second reply", result(second));

            final Future<String> third = async(() -> endpoint.request("third"));
            RequestReplyMessage<String> thirdRequest = transport.next();
            assertFalse(first.isDone());
            transport.deliver(thirdRequest.response("third reply"));
            transport.deliver(firstRequest.response("first reply"));
            assertEquals("third reply", result(third));
            assertEquals("first reply", result(first));
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void rejectsExcessHandlersWithoutQueueingAndReusesTheSlotAfterExit() throws Exception {
        var transport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var handlerThread = new AtomicReference<Thread>();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1));
             var registration = endpoint.handle(payload -> {
                 calls.incrementAndGet();
                 handlerThread.set(Thread.currentThread());
                 entered.countDown();
                 await(release);
                 return "reply:" + payload;
             })) {
            try {
                var first = request("first");
                Thread firstDelivery = start(() -> transport.deliver(first));
                await(entered);
                assertSame(firstDelivery, handlerThread.get());
                var excess = request("excess");

                transport.deliver(excess);
                assertEquals(excess.failure(RequestReplyMessage.Error.OVERLOADED), transport.next());
                transport.deliver(first);
                assertEquals(1, calls.get());
                assertTrue(transport.sent.isEmpty());

                release.countDown();
                assertEquals(first.response("reply:first"), transport.next());
                join(firstDelivery);
                var next = request("next");
                transport.deliver(next);
                assertEquals(next.response("reply:next"), transport.next());
                assertSame(Thread.currentThread(), handlerThread.get());
                assertEquals(2, calls.get());
                assertTrue(transport.sent.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void treatsIncomingRequestIdentityAsThePairOfOriginAndRequestId() throws Exception {
        var transport = new MemoryTransport();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var handlerThreads = new ConcurrentHashMap<String, Thread>();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 2));
             var registration = endpoint.handle(payload -> {
                 calls.incrementAndGet();
                 handlerThreads.put(payload, Thread.currentThread());
                 entered.countDown();
                 await(release);
                 return "reply:" + payload;
             })) {
            try {
                UUID requestId = UUID.randomUUID();
                var first = RequestReplyMessage.request(requestId, UUID.randomUUID(), "first origin");
                var second = RequestReplyMessage.request(requestId, UUID.randomUUID(), "second origin");
                Thread firstDelivery = start(() -> transport.deliver(first));
                Thread secondDelivery = start(() -> transport.deliver(second));
                await(entered);
                assertSame(firstDelivery, handlerThreads.get("first origin"));
                assertSame(secondDelivery, handlerThreads.get("second origin"));
                transport.deliver(first);
                transport.deliver(second);
                assertEquals(2, calls.get());
                assertTrue(transport.sent.isEmpty());

                release.countDown();

                assertEquals(Set.of(first.response("reply:first origin"), second.response("reply:second origin")),
                    Set.of(transport.next(), transport.next()));
                for (Thread thread : handlerThreads.values()) {
                    join(thread);
                }
                assertEquals(2, calls.get());
                assertTrue(transport.sent.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closingAnOldRegistrationCannotRemoveItsReplacementOrCancelAcceptedWork() throws Exception {
        var transport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 2))) {
            try (var original = endpoint.handle(payload -> {
                entered.countDown();
                await(release);
                return "original reply";
            })) {
                try {
                    assertThrows(IllegalStateException.class, () -> endpoint.handle(payload -> "duplicate handler"));
                    var accepted = request("accepted");
                    Thread delivery = start(() -> transport.deliver(accepted));
                    await(entered);
                    original.close();
                    try (var replacement = endpoint.handle(payload -> "replacement reply")) {
                        original.close();
                        var next = request("next");
                        transport.deliver(next);
                        assertEquals(next.response("replacement reply"), transport.next());
                        release.countDown();
                        assertEquals(accepted.response("original reply"), transport.next());
                        join(delivery);
                        assertEquals(1, transport.listeners.size());
                        assertEquals(0, transport.subscriptionCloses.get());
                    }
                    var unhandled = request("unhandled");
                    transport.deliver(unhandled);
                    assertEquals(unhandled.failure(RequestReplyMessage.Error.NO_HANDLER), transport.next());
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void closingFailsPendingWorkCancelsHandlersAndOnlyClosesItsOwnSubscription() throws Exception {
        try (var transport = new MemoryTransport();
             var endpoint = RequestReplyEndpoint.open(transport);
             var other = RequestReplyEndpoint.open(transport)) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var handlerThread = new AtomicReference<Thread>();
            try (var registration = endpoint.handle(payload -> {
                handlerThread.set(Thread.currentThread());
                entered.countDown();
                try {
                    await(release);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
                return "stale reply";
            })) {
                try {
                    final Future<String> pending = async(() -> endpoint.request("pending"));
                    RequestReplyMessage<String> outgoing = transport.next();
                    RequestReplyTransport.Listener<String> oldListener = transport.listeners.get(outgoing.replyTo());
                    assertNotNull(oldListener);
                    start(() -> oldListener.onMessage(request("incoming")));
                    await(entered);

                    endpoint.close();

                    failure(RequestReplyException.Reason.CLOSED, pending);
                    await(interrupted);
                    join(handlerThread.get());
                    assertEquals(1, transport.subscriptionCloses.get());
                    assertEquals(1, transport.listeners.size());
                    assertFalse(transport.closed);
                    assertFalse(transport.listeners.containsKey(outgoing.replyTo()));
                    failure(RequestReplyException.Reason.CLOSED, () -> endpoint.request("closed"));
                    assertThrows(IllegalStateException.class, () -> endpoint.handle(payload -> payload));
                    oldListener.onMessage(request("after close"));
                    oldListener.onMessage(outgoing.response("late reply"));
                    oldListener.onDisconnect();
                    endpoint.close();
                    assertEquals(1, transport.subscriptionCloses.get());
                    assertTrue(transport.sent.isEmpty());

                    Future<String> surviving = async(() -> other.request("surviving endpoint"));
                    RequestReplyMessage<String> request = transport.next();
                    transport.deliver(request.response("still open"));
                    assertEquals("still open", result(surviving));
                } finally {
                    release.countDown();
                }
            }
        }
    }

    @Test
    void disconnectFailsAllPendingRequestsAndAllowsFreshWorkWithoutReplayingOldRequests() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(2, 1))) {
            Future<String> first = async(() -> endpoint.request("first"));
            final RequestReplyMessage<String> firstRequest = transport.next();
            Future<String> second = async(() -> endpoint.request("second"));
            final RequestReplyMessage<String> secondRequest = transport.next();

            transport.disconnect();

            failure(RequestReplyException.Reason.DISCONNECTED, first);
            failure(RequestReplyException.Reason.DISCONNECTED, second);
            assertTrue(transport.sent.isEmpty());
            final Future<String> fresh = async(() -> endpoint.request("fresh"));
            RequestReplyMessage<String> freshRequest = transport.next();
            assertEquals("fresh", freshRequest.payload());
            assertEquals(firstRequest.replyTo(), freshRequest.replyTo());
            transport.deliver(firstRequest.response("old first reply"));
            transport.deliver(secondRequest.failure(RequestReplyMessage.Error.HANDLER_FAILED));
            assertFalse(fresh.isDone());
            transport.deliver(freshRequest.response("fresh reply"));

            assertEquals("fresh reply", result(fresh));
            assertTrue(transport.sent.isEmpty());
            assertEquals(0, transport.subscriptionCloses.get());
            assertEquals(1, transport.listeners.size());
        }
    }

    @Test
    void disconnectKeepsInterruptIgnoringHandlersCountedAndInvalidatesTheirReplies() throws Exception {
        var transport = new MemoryTransport();
        transport.sender = message -> {
            start(() -> transport.deliver(message));
            return CompletableFuture.completedFuture(null);
        };
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var oldHandler = new AtomicReference<Thread>();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1));
             var registration = endpoint.handle(payload -> {
                 calls.incrementAndGet();
                 if ("old".equals(payload)) {
                     oldHandler.set(Thread.currentThread());
                     entered.countDown();
                     awaitIgnoringInterrupts(release, interrupted);
                 }
                 return "reply:" + payload;
             })) {
            try {
                Future<String> pending = async(() -> endpoint.request("old"));
                final RequestReplyMessage<String> oldRequest = transport.next();
                await(entered);

                transport.disconnect();

                failure(RequestReplyException.Reason.DISCONNECTED, pending);
                await(interrupted);
                for (int index = 0; index < 4; index++) {
                    transport.disconnect();
                    RequestReplyException failure = failure(RequestReplyException.Reason.REMOTE_ERROR,
                        () -> endpoint.request("excess"));
                    assertEquals(RequestReplyMessage.Error.OVERLOADED, failure.remoteError());
                    RequestReplyMessage<String> excess = transport.next();
                    assertEquals(excess.failure(RequestReplyMessage.Error.OVERLOADED), transport.next());
                    assertEquals(1, calls.get());
                    assertTrue(oldHandler.get().isAlive());
                }

                release.countDown();
                join(oldHandler.get());
                assertTrue(transport.sent.isEmpty());
                transport.deliver(oldRequest.response("late reply"));
                assertEquals("reply:fresh", endpoint.request("fresh"));
                RequestReplyMessage<String> fresh = transport.next();
                assertEquals(fresh.response("reply:fresh"), transport.next());
                assertEquals(2, calls.get());
                assertEquals(1, transport.listeners.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsInvalidRequestArgumentsBeforeSendingOrConsumingCapacity() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RequestReplyEndpoint.open(transport, options(1, 1))) {
            assertThrows(NullPointerException.class, () -> endpoint.request(null));
            assertThrows(NullPointerException.class, () -> endpoint.request("request", null));
            assertThrows(NullPointerException.class, () -> endpoint.handle(null));
            for (Duration timeout : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.request("request", timeout));
            }
            assertTrue(transport.sent.isEmpty());
            transport.sender = message -> {
                transport.deliver(message.response("valid reply"));
                return CompletableFuture.completedFuture(null);
            };
            assertEquals("valid reply", endpoint.request("valid", WAIT));
        }
    }

    @Test
    void propagatesSubscriptionFailureBeforePublishingAnEndpoint() {
        var expected = new RequestReplyException(RequestReplyException.Reason.DISCONNECTED);
        var transport = new RequestReplyTransport<String>() {
            @NotNull
            @Override
            public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<String> listener)
                    throws RequestReplyException {
                throw expected;
            }

            @NotNull
            @Override
            public CompletionStage<Void> send(@NotNull RequestReplyMessage<String> message) {
                throw new AssertionError("Subscription failure must prevent sending");
            }
        };

        assertSame(expected, assertThrows(RequestReplyException.class, () -> RequestReplyEndpoint.open(transport)));
        assertThrows(NullPointerException.class, () -> RequestReplyEndpoint.open(null));
        assertThrows(NullPointerException.class, () -> RequestReplyEndpoint.open(new MemoryTransport(), null));
    }

    @NotNull
    private static RequestReplyOptions options(int pending, int handlers) {
        return new RequestReplyOptions(WAIT, pending, handlers);
    }

    @NotNull
    private static RequestReplyMessage<String> request(@NotNull String payload) {
        return RequestReplyMessage.request(UUID.randomUUID(), UUID.randomUUID(), payload);
    }

    @NotNull
    private <T> FutureTask<T> async(@NotNull Callable<T> action) {
        var result = new FutureTask<>(action);
        start(result);
        return result;
    }

    @NotNull
    private Thread start(@NotNull Runnable action) {
        Thread thread = Thread.ofVirtual().start(action);
        callers.add(thread);
        return thread;
    }

    @Nullable
    private static <T> T result(@NotNull Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @NotNull
    private static RequestReplyException failure(@NotNull RequestReplyException.Reason reason, @NotNull Executable action) {
        RequestReplyException failure = assertThrows(RequestReplyException.class, action);
        assertEquals(reason, failure.reason());
        return failure;
    }

    @NotNull
    private static RequestReplyException failure(@NotNull RequestReplyException.Reason reason, @NotNull Future<?> future) {
        ExecutionException wrapper = assertThrows(ExecutionException.class, () -> result(future));
        RequestReplyException failure = assertInstanceOf(RequestReplyException.class, wrapper.getCause());
        assertEquals(reason, failure.reason());
        return failure;
    }

    private static void await(@NotNull CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "Latch was not released");
    }

    private static void join(@NotNull Thread thread) throws InterruptedException {
        assertTrue(thread.join(WAIT), "Thread did not finish");
    }

    private static void awaitIgnoringInterrupts(@NotNull CountDownLatch release, @NotNull CountDownLatch interrupted) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        boolean released = false;
        while (!released) {
            try {
                released = release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertTrue(released, "Interrupt-ignoring handler was not released");
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        }
    }

    private void connect(@NotNull MemoryTransport first, @NotNull MemoryTransport second) {
        first.sender = message -> {
            start(() -> second.deliver(message));
            return CompletableFuture.completedFuture(null);
        };
        second.sender = message -> {
            start(() -> first.deliver(message));
            return CompletableFuture.completedFuture(null);
        };
    }

    private static final class MemoryTransport implements RequestReplyTransport<String>, AutoCloseable {
        private final Map<UUID, Listener<String>> listeners = new ConcurrentHashMap<>();
        private final BlockingQueue<RequestReplyMessage<String>> sent = new LinkedBlockingQueue<>();
        private final AtomicInteger subscriptionCloses = new AtomicInteger();
        private volatile Function<RequestReplyMessage<String>, CompletionStage<Void>> sender =
            message -> CompletableFuture.completedFuture(null);
        @Nullable
        private Executor requestExecutor;
        private boolean closed;

        @NotNull
        @Override
        public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<String> listener) {
            assertFalse(closed);
            assertNull(listeners.putIfAbsent(endpointId, listener));
            return () -> {
                if (listeners.remove(endpointId, listener)) {
                    subscriptionCloses.incrementAndGet();
                }
            };
        }

        @NotNull
        @Override
        public CompletionStage<Void> send(@NotNull RequestReplyMessage<String> message) {
            assertFalse(closed);
            sent.add(message);
            return sender.apply(message);
        }

        @Override
        public void executeRequest(@NotNull Runnable request) {
            if (requestExecutor == null) {
                RequestReplyTransport.super.executeRequest(request);
            } else {
                requestExecutor.execute(request);
            }
        }

        private void deliver(@NotNull RequestReplyMessage<String> message) {
            List.copyOf(listeners.values()).forEach(listener -> listener.onMessage(message));
        }

        private void disconnect() {
            List.copyOf(listeners.values()).forEach(Listener::onDisconnect);
        }

        @NotNull
        private RequestReplyMessage<String> next() throws InterruptedException {
            RequestReplyMessage<String> message = sent.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(message, "No message was sent");
            return message;
        }

        @Override
        public void close() {
            closed = true;
            listeners.clear();
        }
    }
}
