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
import com.dbeaver.messaging.requestreply.MessageSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyException;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import com.dbeaver.messaging.rpc.RpcMessage;
import com.dbeaver.messaging.rpc.RpcTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;

public final class DDRpcTransport implements RequestReplyTransport<RpcMessage<JsonElement>> {
    private static final String PROTOCOL = "dbeaver.rpc.v2";
    private static final String FIELD_PROTOCOL = "protocol";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_OPERATION = "operation";
    private static final String FIELD_DATA = "data";
    private static final int MAX_PENDING_SENDS = 128;
    private final Semaphore sendBudget = new Semaphore(MAX_PENDING_SENDS);
    private final RequestReplyTransport<JsonObject> delegate;

    public DDRpcTransport(@NotNull RequestReplyTransport<JsonObject> delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @NotNull
    public static RpcTransport<JsonElement> forChannel(@NotNull DDMessageChannel channel) {
        Objects.requireNonNull(channel);
        return topic -> forChannel(channel, topic);
    }

    @NotNull
    public static DDRpcTransport forChannel(@NotNull DDMessageChannel channel, @NotNull String topic) {
        return new DDRpcTransport(DDRequestReplyTransport.forChannel(channel, topic));
    }

    @Override
    @NotNull
    public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<RpcMessage<JsonElement>> listener)
            throws RequestReplyException {
        Objects.requireNonNull(endpointId);
        Objects.requireNonNull(listener);
        return delegate.subscribe(endpointId, new Listener<>() {
            @Override
            public void onMessage(@NotNull RequestReplyMessage<JsonObject> message) {
                RequestReplyMessage<RpcMessage<JsonElement>> decoded = decode(message);
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
    public CompletionStage<Void> send(@NotNull RequestReplyMessage<RpcMessage<JsonElement>> message) {
        if (!sendBudget.tryAcquire()) {
            return CompletableFuture.failedFuture(new RequestReplyException(RequestReplyException.Reason.OVERLOADED));
        }
        try {
            RpcMessage<JsonElement> payload = message.payload();
            if (payload != null) {
                requireType(message.type(), payload.type());
            }
            RequestReplyMessage<JsonObject> frame = new RequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(),
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
    private static JsonObject encode(@NotNull RpcMessage<JsonElement> message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(FIELD_PROTOCOL, PROTOCOL);
        envelope.addProperty(FIELD_TYPE, message.type().name());
        if (message.type() == RpcMessage.Type.ERROR) {
            envelope.addProperty(FIELD_ERROR, Objects.requireNonNull(message.error()).name());
        } else {
            if (message.type() == RpcMessage.Type.REQUEST) {
                envelope.addProperty(FIELD_OPERATION, message.operation());
            }
            envelope.add(FIELD_DATA, message.payload() == null ? JsonNull.INSTANCE : message.payload().deepCopy());
        }
        return envelope;
    }

    @Nullable
    private static RequestReplyMessage<RpcMessage<JsonElement>> decode(@NotNull RequestReplyMessage<JsonObject> message) {
        if (message.type() == RequestReplyMessage.Type.ERROR || message.payload() == null) {
            return new RequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), null, message.error());
        }
        try {
            JsonObject envelope = message.payload();
            if (!PROTOCOL.equals(string(envelope, FIELD_PROTOCOL))) {
                return null;
            }
            RpcMessage.Type type = RpcMessage.Type.valueOf(string(envelope, FIELD_TYPE));
            requireType(message.type(), type);
            RpcMessage<JsonElement> payload;
            if (type == RpcMessage.Type.ERROR) {
                if (envelope.size() != 3) {
                    return null;
                }
                payload = RpcMessage.failure(RpcMessage.Error.valueOf(string(envelope, FIELD_ERROR)));
            } else {
                JsonElement data = envelope.get(FIELD_DATA);
                if (data == null || envelope.size() != (type == RpcMessage.Type.REQUEST ? 4 : 3)) {
                    return null;
                }
                JsonElement snapshot = data.isJsonNull() ? null : data.deepCopy();
                payload = type == RpcMessage.Type.REQUEST
                    ? RpcMessage.request(string(envelope, FIELD_OPERATION), snapshot)
                    : RpcMessage.response(snapshot);
            }
            return new RequestReplyMessage<>(message.requestId(), message.replyTo(), message.type(), payload, message.error());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void requireType(@NotNull RequestReplyMessage.Type outer, @NotNull RpcMessage.Type inner) {
        if (outer == RequestReplyMessage.Type.REQUEST ? inner != RpcMessage.Type.REQUEST
            : outer != RequestReplyMessage.Type.RESPONSE || inner == RpcMessage.Type.REQUEST) {
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
