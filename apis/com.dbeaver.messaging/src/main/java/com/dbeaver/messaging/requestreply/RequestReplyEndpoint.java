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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RequestReplyEndpoint<P> implements AutoCloseable {
    private static final Logger log = Logger.getLogger(RequestReplyEndpoint.class.getName());
    private final Object lock = new Object();
    private final UUID endpointId = UUID.randomUUID();
    private final RequestReplyTransport<P> transport;
    private final RequestReplyOptions options;
    private final LongSupplier nanoTime;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<RequestKey, Incoming> incoming = new HashMap<>();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final MessageSubscription subscription;
    @Nullable
    private HandlerRegistration handler;
    private boolean closed;
    private long generation;

    RequestReplyEndpoint(
        @NotNull RequestReplyTransport<P> transport,
        @NotNull RequestReplyOptions options,
        @NotNull LongSupplier nanoTime
    ) throws RequestReplyException {
        this.transport = Objects.requireNonNull(transport);
        this.options = Objects.requireNonNull(options);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        try {
            this.subscription = Objects.requireNonNull(transport.subscribe(endpointId, new RequestReplyTransport.Listener<>() {
                @Override
                public void onMessage(@NotNull RequestReplyMessage<P> message) {
                    receive(message);
                }

                @Override
                public void onDisconnect() {
                    disconnect();
                }
            }));
        } catch (RequestReplyException | RuntimeException e) {
            handlers.shutdownNow();
            throw e;
        }
    }

    @NotNull
    public static <P> RequestReplyEndpoint<P> open(@NotNull RequestReplyTransport<P> transport) throws RequestReplyException {
        return open(transport, RequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> RequestReplyEndpoint<P> open(
        @NotNull RequestReplyTransport<P> transport,
        @NotNull RequestReplyOptions options
    ) throws RequestReplyException {
        return new RequestReplyEndpoint<>(transport, options, System::nanoTime);
    }

    @Nullable
    public P request(@NotNull P payload) throws RequestReplyException, InterruptedException {
        return request(payload, options.timeout());
    }

    @Nullable
    public P request(@NotNull P payload, @NotNull Duration timeout) throws RequestReplyException, InterruptedException {
        Objects.requireNonNull(payload);
        long nanos = RequestReplyOptions.requireTimeout(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long started = nanoTime.getAsLong();
        UUID requestId = UUID.randomUUID();
        Pending call = new Pending(started, nanos);
        synchronized (lock) {
            if (closed) {
                throw new RequestReplyException(RequestReplyException.Reason.CLOSED);
            }
            if (pending.size() >= options.maxPendingRequests()) {
                throw new RequestReplyException(RequestReplyException.Reason.OVERLOADED);
            }
            pending.put(requestId, call);
        }
        try {
            if (call.remaining() <= 0) {
                fail(requestId, call, new RequestReplyException(RequestReplyException.Reason.TIMEOUT));
            } else {
                sendRequest(requestId, payload, call);
            }
            try {
                return call.result.get(Math.max(0, call.remaining()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                fail(requestId, call, new RequestReplyException(RequestReplyException.Reason.TIMEOUT));
                return call.result.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RequestReplyException failure) {
                throw failure;
            }
            throw new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e.getCause());
        } finally {
            synchronized (lock) {
                pending.remove(requestId, call);
            }
        }
    }

    @NotNull
    public MessageSubscription handle(@NotNull RequestHandler<P> requestHandler) {
        Objects.requireNonNull(requestHandler);
        synchronized (lock) {
            if (closed || handler != null) {
                throw new IllegalStateException("Request/reply handler registration is unavailable");
            }
            handler = new HandlerRegistration(requestHandler);
            return handler;
        }
    }

    private void sendRequest(@NotNull UUID requestId, @NotNull P payload, @NotNull Pending call) {
        synchronized (lock) {
            if (pending.get(requestId) != call) {
                return;
            }
        }
        try {
            transport.send(RequestReplyMessage.request(requestId, endpointId, payload)).whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(requestId, call, new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, error));
                }
            });
        } catch (RuntimeException e) {
            fail(requestId, call, new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e));
        }
    }

    private void fail(
        @NotNull UUID requestId,
        @NotNull Pending expected,
        @NotNull RequestReplyException failure
    ) {
        synchronized (lock) {
            if (pending.remove(requestId, expected)) {
                expected.result.completeExceptionally(failure);
            }
        }
    }

    private void receive(@NotNull RequestReplyMessage<P> message) {
        if (message.type() == RequestReplyMessage.Type.REQUEST) {
            accept(message);
            return;
        }
        synchronized (lock) {
            if (!closed && endpointId.equals(message.replyTo())) {
                Pending call = pending.remove(message.requestId());
                if (call != null) {
                    if (call.remaining() <= 0) {
                        call.result.completeExceptionally(new RequestReplyException(RequestReplyException.Reason.TIMEOUT));
                    } else if (message.type() == RequestReplyMessage.Type.RESPONSE) {
                        call.result.complete(message.payload());
                    } else {
                        call.result.completeExceptionally(new RequestReplyException(Objects.requireNonNull(message.error())));
                    }
                }
            }
        }
    }

    private void accept(@NotNull RequestReplyMessage<P> message) {
        RequestReplyMessage.Error rejection;
        synchronized (lock) {
            if (closed) {
                return;
            }
            RequestKey key = new RequestKey(message.replyTo(), message.requestId());
            if (incoming.containsKey(key)) {
                return;
            }
            if (handler == null) {
                rejection = RequestReplyMessage.Error.NO_HANDLER;
            } else if (incoming.size() >= options.maxConcurrentHandlers()) {
                rejection = RequestReplyMessage.Error.OVERLOADED;
            } else {
                Incoming accepted = new Incoming(key, message, handler.requestHandler, generation);
                incoming.put(key, accepted);
                try {
                    handlers.execute(accepted);
                    return;
                } catch (RejectedExecutionException e) {
                    incoming.remove(key, accepted);
                    rejection = RequestReplyMessage.Error.OVERLOADED;
                }
            }
        }
        sendReply(message.failure(rejection));
    }

    private void sendReply(@NotNull RequestReplyMessage<P> message) {
        try {
            transport.send(message).whenComplete((ignored, error) -> {
                if (error != null) {
                    log.log(Level.FINE, "Reply could not be sent");
                }
            });
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Reply could not be sent");
        }
    }

    private void disconnect() {
        List<Incoming> stopped;
        synchronized (lock) {
            generation++;
            failPending(RequestReplyException.Reason.DISCONNECTED);
            stopped = List.copyOf(incoming.values());
        }
        stopped.forEach(request -> request.task.cancel(true));
    }

    private void failPending(@NotNull RequestReplyException.Reason reason) {
        pending.values().forEach(call -> call.result.completeExceptionally(new RequestReplyException(reason)));
        pending.clear();
    }

    @Override
    public void close() {
        List<Incoming> stopped;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            handler = null;
            failPending(RequestReplyException.Reason.CLOSED);
            stopped = List.copyOf(incoming.values());
        }
        stopped.forEach(request -> request.task.cancel(true));
        try {
            subscription.close();
        } finally {
            handlers.shutdownNow();
        }
    }

    private record RequestKey(@NotNull UUID replyTo, @NotNull UUID requestId) {
    }

    private final class Pending {
        private final long started;
        private final long nanos;
        private final CompletableFuture<P> result = new CompletableFuture<>();

        private Pending(long started, long nanos) {
            this.started = started;
            this.nanos = nanos;
        }

        private long remaining() {
            return nanos - (nanoTime.getAsLong() - started);
        }
    }

    private final class HandlerRegistration implements MessageSubscription {
        private final RequestHandler<P> requestHandler;

        private HandlerRegistration(@NotNull RequestHandler<P> requestHandler) {
            this.requestHandler = requestHandler;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (handler == this) {
                    handler = null;
                }
            }
        }
    }

    private final class Incoming implements Runnable {
        private final RequestKey key;
        private final RequestReplyMessage<P> message;
        private final RequestHandler<P> requestHandler;
        private final long acceptedGeneration;
        private final FutureTask<Void> task;

        private Incoming(
            @NotNull RequestKey key,
            @NotNull RequestReplyMessage<P> message,
            @NotNull RequestHandler<P> requestHandler,
            long acceptedGeneration
        ) {
            this.key = key;
            this.message = message;
            this.requestHandler = requestHandler;
            this.acceptedGeneration = acceptedGeneration;
            this.task = new FutureTask<>(() -> {
                process();
                return null;
            });
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                synchronized (lock) {
                    incoming.remove(key, this);
                }
            }
        }

        private void process() {
            RequestReplyMessage<P> reply;
            try {
                reply = message.response(requestHandler.handle(Objects.requireNonNull(message.payload())));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                reply = message.failure(RequestReplyMessage.Error.HANDLER_FAILED);
            }
            synchronized (lock) {
                if (closed || acceptedGeneration != generation) {
                    return;
                }
                incoming.remove(key, this);
            }
            sendReply(reply);
        }
    }
}
