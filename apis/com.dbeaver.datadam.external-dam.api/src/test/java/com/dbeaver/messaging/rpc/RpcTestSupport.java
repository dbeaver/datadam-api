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
package com.dbeaver.messaging.rpc;

import com.dbeaver.messaging.requestreply.MessageSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcTestSupport {
    static final Duration WAIT = Duration.ofSeconds(5);
    static final RequestReplyOptions OPTIONS = new RequestReplyOptions(WAIT, 16, 16);
    private final List<Thread> callers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopCallers() throws InterruptedException {
        callers.forEach(Thread::interrupt);
        for (Thread caller : callers) {
            assertTrue(caller.join(WAIT), "Caller did not finish");
        }
    }

    @NotNull
    final <T> FutureTask<T> async(@NotNull Callable<T> action) {
        var future = new FutureTask<>(action);
        start(future);
        return future;
    }

    @NotNull
    final Thread start(@NotNull Runnable action) {
        Thread thread = Thread.ofVirtual().start(action);
        callers.add(thread);
        return thread;
    }

    @Nullable
    static <T> T result(@NotNull Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @NotNull
    static RpcException failure(@NotNull RpcException.Reason reason, @NotNull Executable action) {
        RpcException failure = assertThrows(RpcException.class, action);
        assertEquals(reason, failure.reason());
        return failure;
    }

    @NotNull
    static RpcException failure(@NotNull RpcException.Reason reason, @NotNull Future<?> future) {
        ExecutionException wrapper = assertThrows(ExecutionException.class, () -> result(future));
        RpcException failure = assertInstanceOf(RpcException.class, wrapper.getCause());
        assertEquals(reason, failure.reason());
        return failure;
    }

    @NotNull
    static RpcException remoteFailure(@NotNull RpcMessage.Error error, @NotNull Executable action) {
        RpcException failure = failure(RpcException.Reason.REMOTE_ERROR, action);
        assertEquals(error, failure.remoteError());
        if (failure.remoteException() == null) {
            assertNull(failure.getCause());
        } else {
            Throwable cause = failure.getCause();
            assertNotNull(cause);
            assertEquals(0, cause.getStackTrace().length);
            assertTrue(cause.getMessage().contains(failure.remoteException().stackTrace()));
        }
        return failure;
    }

    static void await(@NotNull CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "Latch was not released");
    }

    final void connect(@NotNull MemoryTransport first, @NotNull MemoryTransport second) {
        first.sender = message -> {
            start(() -> second.deliver(first.routes.get(message.requestId()), message));
            return CompletableFuture.completedFuture(null);
        };
        second.sender = message -> {
            start(() -> first.deliver(second.routes.get(message.requestId()), message));
            return CompletableFuture.completedFuture(null);
        };
    }

    @FunctionalInterface
    interface Conversion {
        @Nullable
        Object apply(@Nullable Object value, @NotNull Type type) throws Exception;
    }

    record CodecCall(@Nullable Object value, @NotNull Type type) {
    }

    static final class TestCodec implements RpcCodec<Object> {
        final List<CodecCall> encoded = new CopyOnWriteArrayList<>();
        final List<CodecCall> decoded = new CopyOnWriteArrayList<>();
        volatile Conversion encoder = (value, type) -> value;
        volatile Conversion decoder = (value, type) -> value;

        @Nullable
        @Override
        public Object encode(@Nullable Object value, @NotNull Type type) throws Exception {
            encoded.add(new CodecCall(value, type));
            return encoder.apply(value, type);
        }

        @Nullable
        @Override
        public Object decode(@Nullable Object payload, @NotNull Type type) throws Exception {
            decoded.add(new CodecCall(payload, type));
            return decoder.apply(payload, type);
        }
    }

    static final class MemoryTransport implements RpcTransport<Object> {
        final Map<UUID, RequestReplyTransport.Listener<RpcMessage<Object>>> listeners = new ConcurrentHashMap<>();
        final Map<UUID, String> listenerTopics = new ConcurrentHashMap<>();
        final Map<UUID, String> routes = new ConcurrentHashMap<>();
        final BlockingQueue<RequestReplyMessage<RpcMessage<Object>>> sent = new LinkedBlockingQueue<>();
        final AtomicInteger subscriptionCloses = new AtomicInteger();
        volatile Function<RequestReplyMessage<RpcMessage<Object>>, CompletionStage<Void>> sender =
            message -> CompletableFuture.completedFuture(null);

        @NotNull
        @Override
        public RequestReplyTransport<RpcMessage<Object>> forTopic(@NotNull String topic) {
            return new RequestReplyTransport<>() {
                @NotNull
                @Override
                public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<RpcMessage<Object>> listener) {
                    assertNull(listeners.putIfAbsent(endpointId, listener));
                    listenerTopics.put(endpointId, topic);
                    return () -> {
                        if (listeners.remove(endpointId, listener)) {
                            listenerTopics.remove(endpointId);
                            subscriptionCloses.incrementAndGet();
                        }
                    };
                }

                @NotNull
                @Override
                public CompletionStage<Void> send(@NotNull RequestReplyMessage<RpcMessage<Object>> message) {
                    routes.put(message.requestId(), topic);
                    sent.add(message);
                    return sender.apply(message);
                }
            };
        }

        void deliver(@NotNull RequestReplyMessage<RpcMessage<Object>> message) {
            deliver(routes.get(message.requestId()), message);
        }

        void deliver(@NotNull String topic, @NotNull RequestReplyMessage<RpcMessage<Object>> message) {
            routes.put(message.requestId(), topic);
            List.copyOf(listeners.entrySet()).stream().filter(entry -> topic.equals(listenerTopics.get(entry.getKey())))
                .forEach(entry -> entry.getValue().onMessage(message));
        }

        void disconnect() {
            List.copyOf(listeners.values()).forEach(RequestReplyTransport.Listener::onDisconnect);
        }

        @NotNull
        RequestReplyMessage<RpcMessage<Object>> next() throws InterruptedException {
            RequestReplyMessage<RpcMessage<Object>> message = sent.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(message, "No message was sent");
            return message;
        }
    }
}
