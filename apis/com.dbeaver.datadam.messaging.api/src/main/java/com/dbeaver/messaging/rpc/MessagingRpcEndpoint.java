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

import com.dbeaver.messaging.requestreply.MessagingRequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyOptions;
import com.dbeaver.messaging.requestreply.MessagingSubscription;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MessagingRpcEndpoint implements AutoCloseable {
    private final State<?> state;

    private MessagingRpcEndpoint(@NotNull State<?> state) {
        this.state = state;
    }

    @NotNull
    public static <P> MessagingRpcEndpoint open(@NotNull MessagingRpcTransport<P> transport) {
        return open(transport, MessagingRequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> MessagingRpcEndpoint open(@NotNull MessagingRpcTransport<P> transport, @NotNull MessagingRpcCodec<P> defaultCodec) {
        return open(transport, defaultCodec, MessagingRequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> MessagingRpcEndpoint open(
        @NotNull MessagingRpcTransport<P> transport,
        @NotNull MessagingRequestReplyOptions options
    ) {
        return new MessagingRpcEndpoint(new State<>(transport, null, options));
    }

    @NotNull
    public static <P> MessagingRpcEndpoint open(
        @NotNull MessagingRpcTransport<P> transport,
        @NotNull MessagingRpcCodec<P> defaultCodec,
        @NotNull MessagingRequestReplyOptions options
    ) {
        return new MessagingRpcEndpoint(new State<>(transport, Objects.requireNonNull(defaultCodec), options));
    }

    @NotNull
    public <T> MessagingSubscription register(@NotNull String topic, @NotNull Class<T> type, @NotNull T implementation) {
        return register(topic, type, implementation, state.defaultCodec());
    }

    @NotNull
    public <T> MessagingSubscription register(
        @NotNull String topic,
        @NotNull Class<T> type,
        @NotNull T implementation,
        @NotNull MessagingRpcCodec<?> codec
    ) {
        return state.register(topic, type, implementation, codec);
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type) {
        return proxy(topic, type, state.defaultCodec());
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type, @NotNull Duration timeout) {
        return proxy(topic, type, state.defaultCodec(), timeout);
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type, @NotNull MessagingRpcCodec<?> codec) {
        return proxy(topic, type, codec, state.options.timeout());
    }

    @NotNull
    public <T> T proxy(
        @NotNull String topic,
        @NotNull Class<T> type,
        @NotNull MessagingRpcCodec<?> codec,
        @NotNull Duration timeout
    ) {
        return state.proxy(topic, type, codec, timeout);
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
        private final Object lock = new Object();
        private final MessagingRpcTransport<P> transport;
        @Nullable
        private final MessagingRpcCodec<P> defaultCodec;
        private final MessagingRequestReplyOptions options;
        private final Map<String, Topic> topics = new HashMap<>();
        private boolean closed;
        private boolean closeComplete;

        private State(
            @NotNull MessagingRpcTransport<P> transport,
            @Nullable MessagingRpcCodec<P> defaultCodec,
            @NotNull MessagingRequestReplyOptions options
        ) {
            this.transport = Objects.requireNonNull(transport);
            this.defaultCodec = defaultCodec;
            this.options = Objects.requireNonNull(options);
        }

        @NotNull
        private MessagingRpcCodec<P> defaultCodec() {
            if (defaultCodec == null) {
                throw new IllegalStateException("RPC endpoint has no default codec");
            }
            return defaultCodec;
        }

        @NotNull
        private <T> MessagingSubscription register(
            @NotNull String topic,
            @NotNull Class<T> type,
            @NotNull T implementation,
            @NotNull MessagingRpcCodec<?> codec
        ) {
            requireTopic(topic);
            MessagingRpcCodec<P> bindingCodec = bindingCodec(codec);
            MessagingRpcContract<T> contract = MessagingRpcContract.create(type);
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
        private <T> T proxy(
            @NotNull String topic,
            @NotNull Class<T> type,
            @NotNull MessagingRpcCodec<?> codec,
            @NotNull Duration timeout
        ) {
            requireTopic(topic);
            requireTimeout(timeout);
            MessagingRpcCodec<P> bindingCodec = bindingCodec(codec);
            MessagingRpcContract<T> contract = MessagingRpcContract.create(type);
            Topic selected = topic(topic);
            synchronized (lock) {
                requireOpen();
            }
            Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, arguments) -> {
                if (MessagingRpcContract.isObjectMethod(method)) {
                    return switch (method.getName()) {
                        case "equals" -> instance == Objects.requireNonNull(arguments)[0];
                        case "hashCode" -> System.identityHashCode(instance);
                        case "toString" -> "RPC proxy[" + topic + ", " + type.getName() + "]";
                        default -> throw new IllegalStateException("Unsupported proxy object method");
                    };
                }
                Method operation = Objects.requireNonNull(contract.operation(MessagingRpcContract.operationId(method)));
                return invoke(selected, operation, Objects.requireNonNull(arguments)[0], bindingCodec, timeout);
            });
            return type.cast(proxy);
        }

        @Nullable
        private Object invoke(
            @NotNull Topic topic,
            @NotNull Method operation,
            @Nullable Object argument,
            @NotNull MessagingRpcCodec<P> codec,
            @NotNull Duration timeout
        ) {
            synchronized (lock) {
                requireOpen();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new MessagingRpcException(MessagingRpcException.Reason.INTERRUPTED);
            }
            MessagingRpcMessage<P> request;
            try {
                P payload = codec.encode(argument, operation.getGenericParameterTypes()[0]);
                request = MessagingRpcMessage.request(MessagingRpcContract.operationId(operation), payload);
            } catch (Exception e) {
                throw codecFailure(e);
            }
            MessagingRpcMessage<P> response;
            try {
                response = Objects.requireNonNull(topic.requests).request(request, timeout);
            } catch (MessagingRequestReplyException e) {
                throw new MessagingRpcException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessagingRpcException(MessagingRpcException.Reason.INTERRUPTED, e);
            }
            if (response == null || response.type() == MessagingRpcMessage.Type.REQUEST) {
                throw new MessagingRpcException(MessagingRpcException.Reason.INVALID_RESPONSE);
            }
            if (response.type() == MessagingRpcMessage.Type.ERROR) {
                throw new MessagingRpcException(Objects.requireNonNull(response.error()), response.exception());
            }
            if (operation.getReturnType() == void.class) {
                if (response.payload() != null) {
                    throw new MessagingRpcException(MessagingRpcException.Reason.INVALID_RESPONSE);
                }
                return null;
            }
            Object result;
            try {
                result = codec.decode(response.payload(), operation.getGenericReturnType());
            } catch (Exception e) {
                throw codecFailure(e);
            }
            if (!MessagingRpcContract.accepts(operation.getReturnType(), result)) {
                throw new MessagingRpcException(MessagingRpcException.Reason.INVALID_RESPONSE);
            }
            return result;
        }

        @NotNull
        private MessagingRpcMessage<P> dispatch(@NotNull Topic topic, @NotNull MessagingRpcMessage<P> request) {
            if (request.type() != MessagingRpcMessage.Type.REQUEST) {
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.INVALID_REQUEST);
            }
            ServiceRegistration registration;
            synchronized (lock) {
                if (closed) {
                    return MessagingRpcMessage.failure(MessagingRpcMessage.Error.UNKNOWN_SERVICE);
                }
                registration = topic.registration;
            }
            if (registration == null) {
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.UNKNOWN_SERVICE);
            }
            Method operation = registration.contract.operation(Objects.requireNonNull(request.operation()));
            if (operation == null) {
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.UNKNOWN_OPERATION);
            }
            Object argument;
            try {
                argument = registration.codec.decode(request.payload(), operation.getGenericParameterTypes()[0]);
            } catch (Exception e) {
                restoreInterruption(e);
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.CODEC_FAILED, MessagingRpcExceptionDetails.capture(e));
            }
            if (!MessagingRpcContract.accepts(operation.getParameterTypes()[0], argument)) {
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.INVALID_REQUEST);
            }
            Object result;
            try {
                result = operation.invoke(registration.implementation, argument);
            } catch (InvocationTargetException e) {
                restoreInterruption(e.getCause());
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.HANDLER_FAILED,
                    MessagingRpcExceptionDetails.capture(Objects.requireNonNullElse(e.getCause(), e)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.HANDLER_FAILED, MessagingRpcExceptionDetails.capture(e));
            }
            if (operation.getReturnType() == void.class) {
                return MessagingRpcMessage.response(null);
            }
            try {
                return MessagingRpcMessage.response(registration.codec.encode(result, operation.getGenericReturnType()));
            } catch (Exception e) {
                restoreInterruption(e);
                return MessagingRpcMessage.failure(MessagingRpcMessage.Error.CODEC_FAILED, MessagingRpcExceptionDetails.capture(e));
            }
        }

        @NotNull
        @SuppressWarnings("unchecked")
        private MessagingRpcCodec<P> bindingCodec(@NotNull MessagingRpcCodec<?> codec) {
            return (MessagingRpcCodec<P>) Objects.requireNonNull(codec);
        }

        private static void requireTopic(@NotNull String topic) {
            Objects.requireNonNull(topic);
            if (topic.isBlank() || topic.length() > 256) {
                throw new IllegalArgumentException("Expected a nonblank RPC topic of at most 256 characters");
            }
        }

        private static void requireTimeout(@NotNull Duration timeout) {
            Objects.requireNonNull(timeout);
            try {
                if (timeout.toNanos() > 0) {
                    return;
                }
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("RPC timeout is too large", e);
            }
            throw new IllegalArgumentException("RPC timeout must be positive");
        }

        @NotNull
        private Topic topic(@NotNull String name) {
            Topic selected;
            boolean opening = false;
            synchronized (lock) {
                requireOpen();
                selected = topics.get(name);
                if (selected == null) {
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
                throw new MessagingRpcException(MessagingRpcException.Reason.INTERRUPTED, e);
            } catch (TimeoutException e) {
                throw new MessagingRpcException(MessagingRpcException.Reason.TIMEOUT, e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof MessagingRpcException failure) {
                    throw failure;
                }
                if (e.getCause() instanceof Error failure) {
                    throw failure;
                }
                throw new MessagingRpcException(MessagingRpcException.Reason.SEND_FAILED, e.getCause());
            }
        }

        private void open(@NotNull Topic topic) {
            synchronized (lock) {
                if (closed) {
                    finish(topic);
                    return;
                }
            }
            MessagingRequestReplyEndpoint<MessagingRpcMessage<P>> requests = null;
            try {
                requests = MessagingRequestReplyEndpoint.open(Objects.requireNonNull(transport.forTopic(topic.name)), options);
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
                    topic.result.completeExceptionally(
                        e instanceof MessagingRequestReplyException failure ? new MessagingRpcException(failure) : e);
                }
                return;
            }
            try {
                close(topic);
            } catch (RuntimeException e) {
                throw new MessagingRpcException(MessagingRpcException.Reason.CLOSED, e);
            }
        }

        private void finish(@NotNull Topic topic) {
            topics.remove(topic.name, topic);
            closeComplete = closed && topics.isEmpty();
        }

        @NotNull
        private static MessagingRpcException codecFailure(@NotNull Exception cause) {
            restoreInterruption(cause);
            MessagingRpcException.Reason reason = cause instanceof InterruptedException
                ? MessagingRpcException.Reason.INTERRUPTED : MessagingRpcException.Reason.CODEC_FAILED;
            return new MessagingRpcException(reason, cause);
        }

        private static void restoreInterruption(@Nullable Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new MessagingRpcException(MessagingRpcException.Reason.CLOSED);
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
                    topic.result.completeExceptionally(new MessagingRpcException(MessagingRpcException.Reason.CLOSED));
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
            private MessagingRequestReplyEndpoint<MessagingRpcMessage<P>> requests;
            @Nullable
            private ServiceRegistration registration;
            private boolean opening = true;
            private boolean closing;

            private Topic(@NotNull String name) {
                this.name = name;
            }
        }

        private final class ServiceRegistration implements MessagingSubscription {
            private final Topic topic;
            private final MessagingRpcContract<?> contract;
            private final Object implementation;
            private final MessagingRpcCodec<P> codec;

            private ServiceRegistration(
                @NotNull Topic topic,
                @NotNull MessagingRpcContract<?> contract,
                @NotNull Object implementation,
                @NotNull MessagingRpcCodec<P> codec
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
