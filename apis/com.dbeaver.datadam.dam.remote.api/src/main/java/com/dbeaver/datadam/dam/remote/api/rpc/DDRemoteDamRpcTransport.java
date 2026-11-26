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
package com.dbeaver.datadam.dam.remote.api.rpc;

import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamPayload;
import com.dbeaver.datadam.dam.remote.api.requestreply.DDRemoteDamRequestReplyTransport;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamChannel;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyMessage;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyTransport;
import com.dbeaver.messaging.requestreply.MessagingSubscription;
import com.dbeaver.messaging.rpc.MessagingRpcExceptionDetails;
import com.dbeaver.messaging.rpc.MessagingRpcMessage;
import com.dbeaver.messaging.rpc.MessagingRpcTransport;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public final class DDRemoteDamRpcTransport implements MessagingRequestReplyTransport<MessagingRpcMessage<Object>> {
    private static final String PROTOCOL = "dbeaver.rpc.v2";
    private static final String FIELD_PROTOCOL = "protocol";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_OPERATION = "operation";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_EXCEPTION = "exception";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_STACK_TRACE = "stackTrace";
    private static final String FIELD_TRUNCATED = "truncated";
    private static final int MAX_PENDING_SENDS = 128;
    private final Semaphore sendBudget = new Semaphore(MAX_PENDING_SENDS);
    private final MessagingRequestReplyTransport<Map<String, Object>> delegate;
    @Nullable
    private final Executor requestExecutor;

    public DDRemoteDamRpcTransport(@NotNull MessagingRequestReplyTransport<Map<String, Object>> delegate) {
        this(delegate, null);
    }

    public DDRemoteDamRpcTransport(
        @NotNull MessagingRequestReplyTransport<Map<String, Object>> delegate,
        @Nullable Executor requestExecutor
    ) {
        this.delegate = Objects.requireNonNull(delegate);
        this.requestExecutor = requestExecutor;
    }

    @NotNull
    public static MessagingRpcTransport<Object> forChannel(@NotNull DDRemoteDamChannel channel) {
        Objects.requireNonNull(channel);
        return topic -> forChannel(channel, topic);
    }

    @NotNull
    public static DDRemoteDamRpcTransport forChannel(@NotNull DDRemoteDamChannel channel, @NotNull String topic) {
        return new DDRemoteDamRpcTransport(DDRemoteDamRequestReplyTransport.forChannel(channel, topic));
    }

    @NotNull
    public static DDRemoteDamRpcTransport forChannel(
        @NotNull DDRemoteDamChannel channel,
        @NotNull String topic,
        @NotNull Executor requestExecutor
    ) {
        return new DDRemoteDamRpcTransport(DDRemoteDamRequestReplyTransport.forChannel(channel, topic),
            Objects.requireNonNull(requestExecutor));
    }

    @Override
    public void executeRequest(@NotNull Runnable request) {
        if (requestExecutor == null) {
            delegate.executeRequest(request);
        } else {
            requestExecutor.execute(request);
        }
    }

    @Override
    @NotNull
    public MessagingSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<MessagingRpcMessage<Object>> listener)
            throws MessagingRequestReplyException {
        Objects.requireNonNull(endpointId);
        Objects.requireNonNull(listener);
        return delegate.subscribe(endpointId, new Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<Map<String, Object>> message) {
                MessagingRequestReplyMessage<MessagingRpcMessage<Object>> decoded = decode(message);
                if (decoded != null) {
                    listener.onMessage(decoded);
                }
            }

            @Override
            public void onDisconnect() {
                listener.onDisconnect();
            }
        });
    }

    @Override
    @NotNull
    public CompletionStage<Void> send(@NotNull MessagingRequestReplyMessage<MessagingRpcMessage<Object>> message) {
        if (!sendBudget.tryAcquire()) {
            return CompletableFuture.failedFuture(new MessagingRequestReplyException(MessagingRequestReplyException.Reason.OVERLOADED));
        }
        try {
            MessagingRpcMessage<Object> payload = message.payload();
            if (payload != null) {
                requireType(message.type(), payload.type());
            }
            MessagingRequestReplyMessage<Map<String, Object>> frame = new MessagingRequestReplyMessage<>(
                message.requestId(), message.replyTo(), message.type(),
                payload == null ? null : encode(payload), message.error());
            CompletableFuture<Void> result = new CompletableFuture<>();
            Objects.requireNonNull(delegate.send(frame)).whenComplete((ignored, error) -> {
                sendBudget.release();
                if (error == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(error);
                }
            });
            return result;
        } catch (RuntimeException | Error e) {
            sendBudget.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    @NotNull
    private static Map<String, Object> encode(@NotNull MessagingRpcMessage<Object> message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(FIELD_PROTOCOL, PROTOCOL);
        envelope.put(FIELD_TYPE, message.type().name());
        if (message.type() == MessagingRpcMessage.Type.ERROR) {
            envelope.put(FIELD_ERROR, Objects.requireNonNull(message.error()).name());
            envelope.put(FIELD_EXCEPTION, message.exception() == null ? null : encodeException(message.exception()));
        } else {
            if (message.type() == MessagingRpcMessage.Type.REQUEST) {
                envelope.put(FIELD_OPERATION, message.operation());
                envelope.put(FIELD_DATA, message.arguments());
            } else {
                envelope.put(FIELD_DATA, message.payload());
            }
        }
        return DDRemoteDamPayload.copyOf(envelope);
    }

    @NotNull
    private static Map<String, Object> encodeException(@NotNull MessagingRpcExceptionDetails details) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put(FIELD_TYPE, details.type());
        exception.put(FIELD_MESSAGE, details.message());
        exception.put(FIELD_STACK_TRACE, details.stackTrace());
        exception.put(FIELD_TRUNCATED, details.truncated());
        return exception;
    }

    @Nullable
    private static MessagingRequestReplyMessage<MessagingRpcMessage<Object>> decode(
        @NotNull MessagingRequestReplyMessage<Map<String, Object>> message
    ) {
        if (message.type() == MessagingRequestReplyMessage.Type.ERROR || message.payload() == null) {
            return new MessagingRequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), null, message.error());
        }
        try {
            Map<String, Object> envelope = DDRemoteDamPayload.copyOf(message.payload());
            if (!PROTOCOL.equals(string(envelope, FIELD_PROTOCOL))) {
                return null;
            }
            MessagingRpcMessage.Type type = MessagingRpcMessage.Type.valueOf(string(envelope, FIELD_TYPE));
            requireType(message.type(), type);
            MessagingRpcMessage<Object> payload;
            if (type == MessagingRpcMessage.Type.ERROR) {
                Object exception = envelope.get(FIELD_EXCEPTION);
                if (envelope.size() != 4 || !envelope.containsKey(FIELD_EXCEPTION)) {
                    return null;
                }
                payload = MessagingRpcMessage.failure(MessagingRpcMessage.Error.valueOf(string(envelope, FIELD_ERROR)),
                    decodeException(exception));
            } else {
                Object data = envelope.get(FIELD_DATA);
                if (!envelope.containsKey(FIELD_DATA) || envelope.size() != (type == MessagingRpcMessage.Type.REQUEST ? 4 : 3)) {
                    return null;
                }
                if (type == MessagingRpcMessage.Type.REQUEST) {
                    if (!(data instanceof List<?> arguments)) {
                        return null;
                    }
                    payload = MessagingRpcMessage.request(string(envelope, FIELD_OPERATION), new ArrayList<>(arguments));
                } else {
                    payload = MessagingRpcMessage.response(data);
                }
            }
            return new MessagingRequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), payload, message.error());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static MessagingRpcExceptionDetails decodeException(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected RPC exception details");
        }
        Map<String, Object> exception = DDRemoteDamPayload.copyOf(map);
        Object message = exception.get(FIELD_MESSAGE);
        if (exception.size() != 4 || !exception.containsKey(FIELD_MESSAGE)
            || !(exception.get(FIELD_TRUNCATED) instanceof Boolean truncated)) {
            throw new IllegalArgumentException("Invalid RPC exception details");
        }
        return new MessagingRpcExceptionDetails(string(exception, FIELD_TYPE),
            message == null ? null : string(exception, FIELD_MESSAGE), string(exception, FIELD_STACK_TRACE), truncated);
    }

    private static void requireType(@NotNull MessagingRequestReplyMessage.Type outer, @NotNull MessagingRpcMessage.Type inner) {
        if (outer == MessagingRequestReplyMessage.Type.REQUEST ? inner != MessagingRpcMessage.Type.REQUEST
            : outer != MessagingRequestReplyMessage.Type.RESPONSE || inner == MessagingRpcMessage.Type.REQUEST) {
            throw new IllegalArgumentException("RPC message type does not match its request/reply frame");
        }
    }

    @NotNull
    private static String string(@NotNull Map<String, Object> envelope, @NotNull String field) {
        if (!(envelope.get(field) instanceof String value)) {
            throw new IllegalArgumentException("Invalid RPC field");
        }
        return value;
    }
}
