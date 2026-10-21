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
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class RpcEndpoint implements AutoCloseable {
    private final State<?> state;

    private RpcEndpoint(@NotNull State<?> state) {
        this.state = state;
    }

    @NotNull
    public static <P> RpcEndpoint open(@NotNull RequestReplyEndpoint<RpcMessage<P>> requests, @NotNull RpcCodec<P> codec) {
        return new RpcEndpoint(new State<>(requests, codec));
    }

    @NotNull
    public static <P> RpcEndpoint open(@NotNull RequestReplyTransport<RpcMessage<P>> transport, @NotNull RpcCodec<P> codec)
            throws RequestReplyException {
        return open(transport, codec, RequestReplyOptions.DEFAULT);
    }

    @NotNull
    public static <P> RpcEndpoint open(
        @NotNull RequestReplyTransport<RpcMessage<P>> transport,
        @NotNull RpcCodec<P> codec,
        @NotNull RequestReplyOptions options
    ) throws RequestReplyException {
        Objects.requireNonNull(codec);
        RequestReplyEndpoint<RpcMessage<P>> requests = RequestReplyEndpoint.open(transport, options);
        try {
            return open(requests, codec);
        } catch (RuntimeException e) {
            try {
                requests.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @NotNull
    public <T> MessageSubscription register(@NotNull String topic, @NotNull Class<T> type, @NotNull T implementation) {
        return state.register(topic, type, implementation);
    }

    @NotNull
    public <T> T proxy(@NotNull String topic, @NotNull Class<T> type) {
        return state.proxy(topic, type);
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
        private final RequestReplyEndpoint<RpcMessage<P>> requests;
        private final RpcCodec<P> codec;
        private final MessageSubscription handler;
        private final Map<String, ServiceRegistration> services = new HashMap<>();
        private boolean closed;
        private boolean closeComplete;

        private State(@NotNull RequestReplyEndpoint<RpcMessage<P>> requests, @NotNull RpcCodec<P> codec) {
            this.requests = Objects.requireNonNull(requests);
            this.codec = Objects.requireNonNull(codec);
            this.handler = requests.handle(this::dispatch);
        }

        @NotNull
        private <T> MessageSubscription register(@NotNull String topic, @NotNull Class<T> type, @NotNull T implementation) {
            RpcMessage.requireTopic(topic);
            RpcContract<T> contract = RpcContract.create(type);
            if (!type.isInstance(Objects.requireNonNull(implementation))) {
                throw new IllegalArgumentException("RPC implementation does not implement its contract");
            }
            contract.requireAccessible(implementation);
            synchronized (lock) {
                requireOpen();
                if (services.containsKey(topic)) {
                    throw new IllegalStateException("RPC topic already has a registered service");
                }
                ServiceRegistration registration = new ServiceRegistration(topic, contract, implementation);
                services.put(topic, registration);
                return registration;
            }
        }

        @NotNull
        private <T> T proxy(@NotNull String topic, @NotNull Class<T> type) {
            RpcMessage.requireTopic(topic);
            RpcContract<T> contract = RpcContract.create(type);
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
                return invoke(topic, operation, Objects.requireNonNull(arguments)[0]);
            });
            return type.cast(proxy);
        }

        @Nullable
        private Object invoke(@NotNull String topic, @NotNull Method operation, @Nullable Object argument) {
            synchronized (lock) {
                requireOpen();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new RpcException(RpcException.Reason.INTERRUPTED);
            }
            RpcMessage<P> request;
            try {
                P payload = codec.encode(argument, operation.getGenericParameterTypes()[0]);
                request = RpcMessage.request(topic, RpcContract.operationId(operation), payload);
            } catch (Exception e) {
                throw codecFailure(e);
            }
            RpcMessage<P> response;
            try {
                response = requests.request(request);
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
        private RpcMessage<P> dispatch(@NotNull RpcMessage<P> request) {
            if (request.type() != RpcMessage.Type.REQUEST) {
                return RpcMessage.failure(RpcMessage.Error.INVALID_REQUEST);
            }
            ServiceRegistration registration;
            synchronized (lock) {
                if (closed) {
                    return RpcMessage.failure(RpcMessage.Error.UNKNOWN_SERVICE);
                }
                registration = services.get(request.topic());
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
                argument = codec.decode(request.payload(), operation.getGenericParameterTypes()[0]);
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
                return RpcMessage.response(codec.encode(result, operation.getGenericReturnType()));
            } catch (Exception e) {
                restoreInterruption(e);
                return RpcMessage.failure(RpcMessage.Error.CODEC_FAILED);
            }
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
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                services.clear();
            }
            try {
                try {
                    handler.close();
                } finally {
                    requests.close();
                }
            } finally {
                synchronized (lock) {
                    closeComplete = true;
                }
            }
        }

        private final class ServiceRegistration implements MessageSubscription {
            private final String topic;
            private final RpcContract<?> contract;
            private final Object implementation;

            private ServiceRegistration(@NotNull String topic, @NotNull RpcContract<?> contract, @NotNull Object implementation) {
                this.topic = topic;
                this.contract = contract;
                this.implementation = implementation;
            }

            @Override
            public void close() {
                synchronized (lock) {
                    services.remove(topic, this);
                }
            }
        }
    }
}
