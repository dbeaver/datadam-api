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
import com.dbeaver.messaging.requestreply.RequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.RequestReplyException;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RpcEndpoint implements AutoCloseable {
    private final State<?> state;

    private RpcEndpoint(@NotNull State<?> state) {
        this.state = state;
    }

    @NotNull
    public static <P> RpcEndpoint open(@NotNull RpcTransport<P> transport) {
        return open(transport, RequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> RpcEndpoint open(@NotNull RpcTransport<P> transport, @NotNull RpcCodec<P> defaultCodec) {
        return open(transport, defaultCodec, RequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> RpcEndpoint open(
        @NotNull RpcTransport<P> transport,
        @NotNull RequestReplyOptions options
    ) {
        return new RpcEndpoint(new State<>(transport, null, options));
    }

    @NotNull
    public static <P> RpcEndpoint open(
        @NotNull RpcTransport<P> transport,
        @NotNull RpcCodec<P> defaultCodec,
        @NotNull RequestReplyOptions options
    ) {
        return new RpcEndpoint(new State<>(transport, Objects.requireNonNull(defaultCodec), options));
    }

    @NotNull
    public <T> MessageSubscription register(@NotNull String topic, @NotNull Class<T> type, @NotNull T implementation) {
        return register(topic, type, implementation, state.defaultCodec());
    }

    @NotNull
    public <T> MessageSubscription register(
        @NotNull String topic,
        @NotNull Class<T> type,
        @NotNull T implementation,
        @NotNull RpcCodec<?> codec
    ) {
        return state.register(topic, type, implementation, codec);
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type) {
        return proxy(topic, type, state.defaultCodec());
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type, @NotNull RpcCodec<?> codec) {
        return state.proxy(topic, type, codec);
    }

    public boolean isClosed() {
        return state.isClosed();
    }

    public boolean isCloseComplete() {
        return state.isCloseComplete();
    }

    @Override
    public void close() {
        state.close();
    }

    private static final class State<P> {
        private static final int MAX_TOPICS = 64;
        private final Object lock = new Object();
        private final RpcTransport<P> transport;
        @Nullable
        private final RpcCodec<P> defaultCodec;
        private final RequestReplyOptions options;
        private final Map<String, Topic> topics = new HashMap<>();
        private boolean closed;
        private boolean closeComplete;

        private State(@NotNull RpcTransport<P> transport, @Nullable RpcCodec<P> defaultCodec, @NotNull RequestReplyOptions options) {
            this.transport = Objects.requireNonNull(transport);
            this.defaultCodec = defaultCodec;
            this.options = Objects.requireNonNull(options);
        }

        @NotNull
        private RpcCodec<P> defaultCodec() {
            if (defaultCodec == null) {
                throw new IllegalStateException("RPC endpoint has no default codec");
            }
            return defaultCodec;
        }

        @NotNull
        private <T> MessageSubscription register(
            @NotNull String topic,
            @NotNull Class<T> type,
            @NotNull T implementation,
            @NotNull RpcCodec<?> codec
        ) {
            requireTopic(topic);
            RpcCodec<P> bindingCodec = bindingCodec(codec);
            RpcContract<T> contract = RpcContract.create(type);
            if (!type.isInstance(Objects.requireNonNull(implementation))) {
                throw new IllegalArgumentException("RPC implementation does not implement its contract");
            }
            contract.requireAccessible(implementation);
            Topic selected = topic(topic);
            synchronized (lock) {
                requireOpen();
                if (selected.registration != null) {
                    throw new IllegalStateException("RPC topic already has a registered service");
                }
                ServiceRegistration registration = new ServiceRegistration(selected, contract, implementation, bindingCodec);
                selected.registration = registration;
                return registration;
            }
        }

        @NotNull
        private <T> T proxy(@NotNull String topic, @NotNull Class<T> type, @NotNull RpcCodec<?> codec) {
            requireTopic(topic);
            RpcCodec<P> bindingCodec = bindingCodec(codec);
            RpcContract<T> contract = RpcContract.create(type);
            Topic selected = topic(topic);
            synchronized (lock) {
                requireOpen();
            }
            Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, arguments) -> {
                if (RpcContract.isObjectMethod(method)) {
                    return switch (method.getName()) {
                        case "equals" -> instance == Objects.requireNonNull(arguments)[0];
                        case "hashCode" -> System.identityHashCode(instance);
                        case "toString" -> "RPC proxy[" + topic + ", " + type.getName() + "]";
                        default -> throw new IllegalStateException("Unsupported proxy object method");
                    };
                }
                Method operation = Objects.requireNonNull(contract.operation(RpcContract.operationId(method)));
                return invoke(selected, operation, Objects.requireNonNull(arguments)[0], bindingCodec);
            });
            return type.cast(proxy);
        }

        @Nullable
        private Object invoke(
            @NotNull Topic topic,
            @NotNull Method operation,
            @Nullable Object argument,
            @NotNull RpcCodec<P> codec
        ) {
            synchronized (lock) {
                requireOpen();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new RpcException(RpcException.Reason.INTERRUPTED);
            }
            RpcMessage<P> request;
            try {
                P payload = codec.encode(argument, operation.getGenericParameterTypes()[0]);
                request = RpcMessage.request(RpcContract.operationId(operation), payload);
            } catch (Exception e) {
                throw codecFailure(e);
            }
            RpcMessage<P> response;
            try {
                response = Objects.requireNonNull(topic.requests).request(request);
            } catch (RequestReplyException e) {
                throw new RpcException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException(RpcException.Reason.INTERRUPTED, e);
            }
            if (response == null || response.type() == RpcMessage.Type.REQUEST) {
                throw new RpcException(RpcException.Reason.INVALID_RESPONSE);
            }
            if (response.type() == RpcMessage.Type.ERROR) {
                throw new RpcException(Objects.requireNonNull(response.error()));
            }
            if (operation.getReturnType() == void.class) {
                if (response.payload() != null) {
                    throw new RpcException(RpcException.Reason.INVALID_RESPONSE);
                }
                return null;
            }
            Object result;
            try {
                result = codec.decode(response.payload(), operation.getGenericReturnType());
            } catch (Exception e) {
                throw codecFailure(e);
            }
            if (!RpcContract.accepts(operation.getReturnType(), result)) {
                throw new RpcException(RpcException.Reason.INVALID_RESPONSE);
            }
            return result;
        }

        @NotNull
        private RpcMessage<P> dispatch(@NotNull Topic topic, @NotNull RpcMessage<P> request) {
            if (request.type() != RpcMessage.Type.REQUEST) {
                return RpcMessage.failure(RpcMessage.Error.INVALID_REQUEST);
            }
            ServiceRegistration registration;
            synchronized (lock) {
                if (closed) {
                    return RpcMessage.failure(RpcMessage.Error.UNKNOWN_SERVICE);
                }
                registration = topic.registration;
            }
            if (registration == null) {
                return RpcMessage.failure(RpcMessage.Error.UNKNOWN_SERVICE);
            }
            Method operation = registration.contract.operation(Objects.requireNonNull(request.operation()));
            if (operation == null) {
                return RpcMessage.failure(RpcMessage.Error.UNKNOWN_OPERATION);
            }
            Object argument;
            try {
                argument = registration.codec.decode(request.payload(), operation.getGenericParameterTypes()[0]);
            } catch (Exception e) {
                restoreInterruption(e);
                return RpcMessage.failure(RpcMessage.Error.CODEC_FAILED);
            }
            if (!RpcContract.accepts(operation.getParameterTypes()[0], argument)) {
                return RpcMessage.failure(RpcMessage.Error.INVALID_REQUEST);
            }
            Object result;
            try {
                result = operation.invoke(registration.implementation, argument);
            } catch (InvocationTargetException e) {
                restoreInterruption(e.getCause());
                return RpcMessage.failure(RpcMessage.Error.HANDLER_FAILED);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return RpcMessage.failure(RpcMessage.Error.HANDLER_FAILED);
            }
            if (operation.getReturnType() == void.class) {
                return RpcMessage.response(null);
            }
            try {
                return RpcMessage.response(registration.codec.encode(result, operation.getGenericReturnType()));
            } catch (Exception e) {
                restoreInterruption(e);
                return RpcMessage.failure(RpcMessage.Error.CODEC_FAILED);
            }
        }

        @NotNull
        @SuppressWarnings("unchecked")
        private RpcCodec<P> bindingCodec(@NotNull RpcCodec<?> codec) {
            return (RpcCodec<P>) Objects.requireNonNull(codec);
        }

        private static void requireTopic(@NotNull String topic) {
            Objects.requireNonNull(topic);
            if (topic.isBlank() || topic.length() > 256) {
                throw new IllegalArgumentException("Expected a nonblank RPC topic of at most 256 characters");
            }
        }

        @NotNull
        private Topic topic(@NotNull String name) {
            Topic selected;
            boolean opening = false;
            synchronized (lock) {
                requireOpen();
                selected = topics.get(name);
                if (selected == null) {
                    if (topics.size() >= MAX_TOPICS) {
                        throw new RpcException(RpcException.Reason.OVERLOADED);
                    }
                    selected = new Topic(name);
                    topics.put(name, selected);
                    opening = true;
                }
            }
            if (opening) {
                open(selected);
            }
            try {
                Topic ready = selected.result.get(options.timeout().toNanos(), TimeUnit.NANOSECONDS);
                synchronized (lock) {
                    requireOpen();
                }
                return ready;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RpcException(RpcException.Reason.INTERRUPTED, e);
            } catch (TimeoutException e) {
                throw new RpcException(RpcException.Reason.TIMEOUT, e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RpcException failure) {
                    throw failure;
                }
                if (e.getCause() instanceof Error failure) {
                    throw failure;
                }
                throw new RpcException(RpcException.Reason.SEND_FAILED, e.getCause());
            }
        }

        private void open(@NotNull Topic topic) {
            synchronized (lock) {
                if (closed) {
                    finish(topic);
                    return;
                }
            }
            RequestReplyEndpoint<RpcMessage<P>> requests = null;
            try {
                requests = RequestReplyEndpoint.open(Objects.requireNonNull(transport.forTopic(topic.name)), options);
                requests.handle(request -> dispatch(topic, request));
                synchronized (lock) {
                    topic.requests = requests;
                    topic.opening = false;
                    if (!closed) {
                        topic.result.complete(topic);
                        return;
                    }
                }
            } catch (Throwable e) {
                if (requests != null) {
                    try {
                        requests.close();
                    } catch (RuntimeException | Error closeFailure) {
                        if (closeFailure != e) {
                            e.addSuppressed(closeFailure);
                        }
                    }
                }
                synchronized (lock) {
                    finish(topic);
                    topic.result.completeExceptionally(e instanceof RequestReplyException failure ? new RpcException(failure) : e);
                }
                return;
            }
            try {
                close(topic);
            } catch (RuntimeException e) {
                throw new RpcException(RpcException.Reason.CLOSED, e);
            }
        }

        private void finish(@NotNull Topic topic) {
            topics.remove(topic.name, topic);
            closeComplete = closed && topics.isEmpty();
        }

        @NotNull
        private static RpcException codecFailure(@NotNull Exception cause) {
            restoreInterruption(cause);
            RpcException.Reason reason = cause instanceof InterruptedException
                ? RpcException.Reason.INTERRUPTED : RpcException.Reason.CODEC_FAILED;
            return new RpcException(reason, cause);
        }

        private static void restoreInterruption(@Nullable Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new RpcException(RpcException.Reason.CLOSED);
            }
        }

        private boolean isClosed() {
            synchronized (lock) {
                return closed;
            }
        }

        private boolean isCloseComplete() {
            synchronized (lock) {
                return closeComplete;
            }
        }

        private void close() {
            List<Topic> stopped;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                stopped = topics.values().stream().filter(topic -> !topic.opening).toList();
                topics.values().forEach(topic -> {
                    topic.registration = null;
                    topic.result.completeExceptionally(new RpcException(RpcException.Reason.CLOSED));
                });
                closeComplete = topics.isEmpty();
            }
            Throwable failure = null;
            for (Topic topic : stopped) {
                try {
                    close(topic);
                } catch (RuntimeException | Error e) {
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private void close(@NotNull Topic topic) {
            synchronized (lock) {
                if (topic.closing) {
                    return;
                }
                topic.closing = true;
            }
            try {
                Objects.requireNonNull(topic.requests).close();
            } finally {
                synchronized (lock) {
                    finish(topic);
                }
            }
        }

        private final class Topic {
            private final String name;
            private final CompletableFuture<Topic> result = new CompletableFuture<>();
            @Nullable
            private RequestReplyEndpoint<RpcMessage<P>> requests;
            @Nullable
            private ServiceRegistration registration;
            private boolean opening = true;
            private boolean closing;

            private Topic(@NotNull String name) {
                this.name = name;
            }
        }

        private final class ServiceRegistration implements MessageSubscription {
            private final Topic topic;
            private final RpcContract<?> contract;
            private final Object implementation;
            private final RpcCodec<P> codec;

            private ServiceRegistration(
                @NotNull Topic topic,
                @NotNull RpcContract<?> contract,
                @NotNull Object implementation,
                @NotNull RpcCodec<P> codec
            ) {
                this.topic = topic;
                this.contract = contract;
                this.implementation = implementation;
                this.codec = codec;
            }

            @Override
            public void close() {
                synchronized (lock) {
                    if (topic.registration == this) {
                        topic.registration = null;
                    }
                }
            }
        }
    }
}
