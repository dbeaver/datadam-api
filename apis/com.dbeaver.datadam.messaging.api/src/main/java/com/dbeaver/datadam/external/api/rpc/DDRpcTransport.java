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
package com.dbeaver.datadam.external.api.rpc;

import com.dbeaver.datadam.external.api.requestreply.DDRequestReplyTransport;
import com.dbeaver.datadam.external.api.transport.DDMessageChannel;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyMessage;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyTransport;
import com.dbeaver.messaging.requestreply.MessagingSubscription;
import com.dbeaver.messaging.rpc.MessagingRpcExceptionDetails;
import com.dbeaver.messaging.rpc.MessagingRpcMessage;
import com.dbeaver.messaging.rpc.MessagingRpcTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public final class DDRpcTransport implements MessagingRequestReplyTransport<MessagingRpcMessage<JsonElement>> {
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
    private final MessagingRequestReplyTransport<JsonObject> delegate;
    @Nullable
    private final Executor requestExecutor;

    public DDRpcTransport(@NotNull MessagingRequestReplyTransport<JsonObject> delegate) {
        this(delegate, null);
    }

    public DDRpcTransport(@NotNull MessagingRequestReplyTransport<JsonObject> delegate, @Nullable Executor requestExecutor) {
        this.delegate = Objects.requireNonNull(delegate);
        this.requestExecutor = requestExecutor;
    }

    @NotNull
    public static MessagingRpcTransport<JsonElement> forChannel(@NotNull DDMessageChannel channel) {
        Objects.requireNonNull(channel);
        return topic -> forChannel(channel, topic);
    }

    @NotNull
    public static DDRpcTransport forChannel(@NotNull DDMessageChannel channel, @NotNull String topic) {
        return new DDRpcTransport(DDRequestReplyTransport.forChannel(channel, topic));
    }

    @NotNull
    public static DDRpcTransport forChannel(
        @NotNull DDMessageChannel channel,
        @NotNull String topic,
        @NotNull Executor requestExecutor
    ) {
        return new DDRpcTransport(DDRequestReplyTransport.forChannel(channel, topic), Objects.requireNonNull(requestExecutor));
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
    public MessagingSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<MessagingRpcMessage<JsonElement>> listener)
            throws MessagingRequestReplyException {
        Objects.requireNonNull(endpointId);
        Objects.requireNonNull(listener);
        return delegate.subscribe(endpointId, new Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                MessagingRequestReplyMessage<MessagingRpcMessage<JsonElement>> decoded = decode(message);
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
    public CompletionStage<Void> send(@NotNull MessagingRequestReplyMessage<MessagingRpcMessage<JsonElement>> message) {
        if (!sendBudget.tryAcquire()) {
            return CompletableFuture.failedFuture(new MessagingRequestReplyException(MessagingRequestReplyException.Reason.OVERLOADED));
        }
        try {
            MessagingRpcMessage<JsonElement> payload = message.payload();
            if (payload != null) {
                requireType(message.type(), payload.type());
            }
            MessagingRequestReplyMessage<JsonObject> frame = new MessagingRequestReplyMessage<>(
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
    private static JsonObject encode(@NotNull MessagingRpcMessage<JsonElement> message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(FIELD_PROTOCOL, PROTOCOL);
        envelope.addProperty(FIELD_TYPE, message.type().name());
        if (message.type() == MessagingRpcMessage.Type.ERROR) {
            envelope.addProperty(FIELD_ERROR, Objects.requireNonNull(message.error()).name());
            envelope.add(FIELD_EXCEPTION, message.exception() == null ? JsonNull.INSTANCE : encodeException(message.exception()));
        } else {
            if (message.type() == MessagingRpcMessage.Type.REQUEST) {
                envelope.addProperty(FIELD_OPERATION, message.operation());
            }
            envelope.add(FIELD_DATA, message.payload() == null ? JsonNull.INSTANCE : message.payload().deepCopy());
        }
        return envelope;
    }

    @NotNull
    private static JsonObject encodeException(@NotNull MessagingRpcExceptionDetails details) {
        JsonObject exception = new JsonObject();
        exception.addProperty(FIELD_TYPE, details.type());
        exception.addProperty(FIELD_MESSAGE, details.message());
        exception.addProperty(FIELD_STACK_TRACE, details.stackTrace());
        exception.addProperty(FIELD_TRUNCATED, details.truncated());
        return exception;
    }

    @Nullable
    private static MessagingRequestReplyMessage<MessagingRpcMessage<JsonElement>> decode(
        @NotNull MessagingRequestReplyMessage<JsonObject> message
    ) {
        if (message.type() == MessagingRequestReplyMessage.Type.ERROR || message.payload() == null) {
            return new MessagingRequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), null, message.error());
        }
        try {
            JsonObject envelope = message.payload();
            if (!PROTOCOL.equals(string(envelope, FIELD_PROTOCOL))) {
                return null;
            }
            MessagingRpcMessage.Type type = MessagingRpcMessage.Type.valueOf(string(envelope, FIELD_TYPE));
            requireType(message.type(), type);
            MessagingRpcMessage<JsonElement> payload;
            if (type == MessagingRpcMessage.Type.ERROR) {
                JsonElement exception = envelope.get(FIELD_EXCEPTION);
                if (envelope.size() != 4 || exception == null) {
                    return null;
                }
                payload = MessagingRpcMessage.failure(MessagingRpcMessage.Error.valueOf(string(envelope, FIELD_ERROR)),
                    decodeException(exception));
            } else {
                JsonElement data = envelope.get(FIELD_DATA);
                if (data == null || envelope.size() != (type == MessagingRpcMessage.Type.REQUEST ? 4 : 3)) {
                    return null;
                }
                JsonElement snapshot = data.isJsonNull() ? null : data.deepCopy();
                payload = type == MessagingRpcMessage.Type.REQUEST
                    ? MessagingRpcMessage.request(string(envelope, FIELD_OPERATION), snapshot)
                    : MessagingRpcMessage.response(snapshot);
            }
            return new MessagingRequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), payload, message.error());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static MessagingRpcExceptionDetails decodeException(@NotNull JsonElement value) {
        if (value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Expected RPC exception details");
        }
        JsonObject exception = value.getAsJsonObject();
        JsonElement message = exception.get(FIELD_MESSAGE);
        JsonElement truncated = exception.get(FIELD_TRUNCATED);
        if (exception.size() != 4 || message == null || truncated == null || !truncated.isJsonPrimitive()
            || !truncated.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid RPC exception details");
        }
        return new MessagingRpcExceptionDetails(string(exception, FIELD_TYPE),
            message.isJsonNull() ? null : string(exception, FIELD_MESSAGE), string(exception, FIELD_STACK_TRACE),
            truncated.getAsBoolean());
    }

    private static void requireType(@NotNull MessagingRequestReplyMessage.Type outer, @NotNull MessagingRpcMessage.Type inner) {
        if (outer == MessagingRequestReplyMessage.Type.REQUEST ? inner != MessagingRpcMessage.Type.REQUEST
            : outer != MessagingRequestReplyMessage.Type.RESPONSE || inner == MessagingRpcMessage.Type.REQUEST) {
            throw new IllegalArgumentException("RPC message type does not match its request/reply frame");
        }
    }

    @NotNull
    private static String string(@NotNull JsonObject envelope, @NotNull String field) {
        JsonElement value = envelope.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid RPC field");
        }
        return value.getAsString();
    }
}
