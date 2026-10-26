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
package com.dbeaver.datadam.external.api.requestreply;

import com.dbeaver.datadam.external.api.exception.DDExternalDamException;
import com.dbeaver.datadam.external.api.model.DDConnectionInfo;
import com.dbeaver.datadam.external.api.model.DDMessage;
import com.dbeaver.datadam.external.api.transport.DDChannelStatus;
import com.dbeaver.datadam.external.api.transport.DDMessageChannel;
import com.dbeaver.datadam.external.api.transport.DDMessageListener;
import com.dbeaver.datadam.external.api.transport.DDSubscription;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyMessage;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyTransport;
import com.dbeaver.messaging.requestreply.MessagingSubscription;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;

public final class DDRequestReplyTransport implements MessagingRequestReplyTransport<JsonObject>, AutoCloseable {
    private static final String MESSAGE_TYPE = "datadam.requestreply.v1";
    private static final String FIELD_REQUEST_ID = "requestId";
    private static final String FIELD_REPLY_TO = "replyTo";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_DATA = "data";
    private static final int MAX_PENDING_SENDS = 128;
    private static final int MAX_PENDING_DELIVERIES = 128;
    private final Object lock = new Object();
    private final Semaphore sendBudget = new Semaphore(MAX_PENDING_SENDS);
    private final String topic;
    private final String replyPrefix;
    private final Sender sender;
    private final TopicSubscriber subscriber;
    @Nullable
    private final DDMessageChannel channel;
    @Nullable
    private Registration registration;
    private boolean claimed;

    /** Sends messages asynchronously to a selected peer. */
    @FunctionalInterface
    public interface Sender {
        @NotNull
        CompletionStage<Void> send(@NotNull DDMessage message);
    }

    /** Registers topic listeners for a selected peer. */
    @FunctionalInterface
    public interface TopicSubscriber {
        @NotNull
        DDSubscription subscribe(@NotNull String topic, @NotNull DDMessageListener listener) throws DDExternalDamException;
    }

    public DDRequestReplyTransport(@NotNull String topic, @NotNull Sender sender, @NotNull TopicSubscriber subscriber) {
        this(topic, sender, subscriber, null);
    }

    private DDRequestReplyTransport(
        @NotNull String topic,
        @NotNull Sender sender,
        @NotNull TopicSubscriber subscriber,
        @Nullable DDMessageChannel channel
    ) {
        if (topic.isBlank() || topic.length() > 256) {
            throw new IllegalArgumentException("Expected a nonblank request/reply topic of at most 256 characters");
        }
        this.topic = topic;
        this.replyPrefix = replyPrefix(topic);
        this.sender = Objects.requireNonNull(sender);
        this.subscriber = Objects.requireNonNull(subscriber);
        this.channel = channel;
    }

    @NotNull
    public static DDRequestReplyTransport forChannel(@NotNull DDMessageChannel channel, @NotNull String topic) {
        Objects.requireNonNull(channel);
        return new DDRequestReplyTransport(topic, channel::send, channel::subscribe, channel);
    }

    @Override
    @NotNull
    public MessagingSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<JsonObject> listener)
            throws MessagingRequestReplyException {
        Registration opened;
        synchronized (lock) {
            if (claimed) {
                throw new IllegalStateException("A request/reply adapter supports only one endpoint and cannot be reopened");
            }
            claimed = true;
            opened = new Registration(Objects.requireNonNull(endpointId), Objects.requireNonNull(listener));
            registration = opened;
        }
        try {
            if (channel != null) {
                opened.state(channel.getStatus());
                opened.requireReady();
                opened.add(channel.observeState(opened::state));
            }
            opened.add(subscriber.subscribe(opened.replyTopic, (connection, message) -> opened.receive(connection, message, false)));
            opened.add(subscriber.subscribe(topic, (connection, message) -> opened.receive(connection, message, true)));
            if (channel != null) {
                opened.state(channel.getStatus());
            }
            synchronized (lock) {
                opened.requireReady();
                opened.opening = false;
            }
            return opened;
        } catch (DDExternalDamException | MessagingRequestReplyException | RuntimeException e) {
            try {
                opened.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            if (e instanceof MessagingRequestReplyException failure) {
                throw failure;
            }
            throw new MessagingRequestReplyException(MessagingRequestReplyException.Reason.SEND_FAILED, e);
        }
    }

    @Override
    @NotNull
    public CompletionStage<Void> send(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
        synchronized (lock) {
            if (registration == null || registration.closed) {
                return CompletableFuture.failedFuture(new MessagingRequestReplyException(MessagingRequestReplyException.Reason.CLOSED));
            }
            if (channel != null && !channel.getStatus().isReady()) {
                return CompletableFuture.failedFuture(
                    new MessagingRequestReplyException(MessagingRequestReplyException.Reason.DISCONNECTED));
            }
            if (message.type() == MessagingRequestReplyMessage.Type.REQUEST && !registration.endpointId.equals(message.replyTo())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Request origin does not match its endpoint"));
            }
        }
        if (!sendBudget.tryAcquire()) {
            return CompletableFuture.failedFuture(new MessagingRequestReplyException(MessagingRequestReplyException.Reason.OVERLOADED));
        }
        try {
            JsonObject envelope = new JsonObject();
            envelope.addProperty(FIELD_REQUEST_ID, message.requestId().toString());
            envelope.addProperty(FIELD_REPLY_TO, message.replyTo().toString());
            envelope.addProperty(FIELD_TYPE, message.type().name());
            if (message.type() == MessagingRequestReplyMessage.Type.ERROR) {
                envelope.addProperty(FIELD_ERROR, Objects.requireNonNull(message.error()).name());
            } else {
                envelope.add(FIELD_DATA, message.payload() == null ? JsonNull.INSTANCE : message.payload());
            }
            String target = message.type() == MessagingRequestReplyMessage.Type.REQUEST ? topic : replyPrefix + message.replyTo();
            CompletableFuture<Void> result = new CompletableFuture<>();
            Objects.requireNonNull(sender.send(new DDMessage(target, MESSAGE_TYPE, envelope))).whenComplete((ignored, error) -> {
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

    @Override
    public void close() {
        Registration stopped;
        synchronized (lock) {
            claimed = true;
            stopped = registration;
        }
        if (stopped != null) {
            stopped.close();
        }
    }

    @NotNull
    private static String replyPrefix(@NotNull String topic) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(topic.getBytes(StandardCharsets.UTF_8));
            return "datadam.reply.v1." + HexFormat.of().formatHex(digest) + ".";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private static MessagingRequestReplyMessage<JsonObject> decode(@NotNull DDMessage message) {
        if (!MESSAGE_TYPE.equals(message.type())) {
            return null;
        }
        try {
            JsonObject envelope = message.payload();
            UUID requestId = uuid(envelope, FIELD_REQUEST_ID);
            UUID replyTo = uuid(envelope, FIELD_REPLY_TO);
            MessagingRequestReplyMessage.Type type = MessagingRequestReplyMessage.Type.valueOf(string(envelope, FIELD_TYPE));
            JsonElement data = envelope.get(FIELD_DATA);
            MessagingRequestReplyMessage.Error error = null;
            JsonObject payload = null;
            if (type == MessagingRequestReplyMessage.Type.ERROR) {
                if (data != null && !data.isJsonNull()) {
                    return null;
                }
                error = MessagingRequestReplyMessage.Error.valueOf(string(envelope, FIELD_ERROR));
            } else {
                if (envelope.has(FIELD_ERROR) || data == null || !data.isJsonObject() && !data.isJsonNull()
                    || type == MessagingRequestReplyMessage.Type.REQUEST && data.isJsonNull()) {
                    return null;
                }
                payload = data.isJsonNull() ? null : data.getAsJsonObject();
            }
            return new MessagingRequestReplyMessage<>(requestId, replyTo, type, payload, error);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @NotNull
    private static String string(@NotNull JsonObject envelope, @NotNull String field) {
        JsonElement value = envelope.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid request/reply field");
        }
        return value.getAsString();
    }

    @NotNull
    private static UUID uuid(@NotNull JsonObject envelope, @NotNull String field) {
        String value = string(envelope, field);
        UUID id = UUID.fromString(value);
        if (value.length() != 36 || !id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid request/reply UUID");
        }
        return id;
    }

    private final class Registration implements MessagingSubscription {
        private final UUID endpointId;
        private final String replyTopic;
        private final Listener<JsonObject> listener;
        private final List<DDSubscription> subscriptions = new ArrayList<>();
        private final ArrayDeque<Delivery> deliveries = new ArrayDeque<>();
        @Nullable
        private DDChannelStatus status;
        private boolean opening = true;
        private boolean openingDisconnected;
        private boolean delivering;
        private boolean closed;

        private Registration(@NotNull UUID endpointId, @NotNull Listener<JsonObject> listener) {
            this.endpointId = endpointId;
            this.replyTopic = replyPrefix + endpointId;
            this.listener = listener;
        }

        private void add(@NotNull DDSubscription subscription) {
            synchronized (lock) {
                if (!closed) {
                    subscriptions.add(Objects.requireNonNull(subscription));
                    return;
                }
            }
            subscription.close();
        }

        private void requireReady() throws MessagingRequestReplyException {
            synchronized (lock) {
                if (closed) {
                    throw new MessagingRequestReplyException(MessagingRequestReplyException.Reason.CLOSED);
                }
                if (openingDisconnected || channel != null && (status == null || !status.isReady())) {
                    throw new MessagingRequestReplyException(MessagingRequestReplyException.Reason.DISCONNECTED);
                }
            }
        }

        private void state(@NotNull DDChannelStatus next) {
            boolean drain;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                openingDisconnected |= opening && !next.isReady();
                drain = updateState(channel == null ? next : channel.getStatus());
            }
            if (drain) {
                drain();
            }
        }

        private boolean updateState(@NotNull DDChannelStatus next) {
            if (next.equals(status)) {
                return false;
            }
            boolean disconnected = !next.isReady() || status != null && status.isReady()
                && !Objects.equals(status.connection(), next.connection());
            status = next;
            openingDisconnected |= opening && disconnected;
            return disconnected && enqueue(null);
        }

        private void receive(@NotNull DDConnectionInfo connection, @NotNull DDMessage message, boolean requestTopic) {
            MessagingRequestReplyMessage<JsonObject> frame = decode(message);
            if (frame == null || (requestTopic ? frame.type() != MessagingRequestReplyMessage.Type.REQUEST
                : frame.type() == MessagingRequestReplyMessage.Type.REQUEST || !endpointId.equals(frame.replyTo()))) {
                return;
            }
            boolean drain = false;
            synchronized (lock) {
                if (closed || !(requestTopic ? topic : replyTopic).equals(message.topic())) {
                    return;
                }
                if (channel != null) {
                    DDChannelStatus current = channel.getStatus();
                    if (!current.isReady() || !connection.equals(current.connection())) {
                        return;
                    }
                    drain = updateState(current);
                    drain |= enqueue(frame);
                }
            }
            if (channel == null) {
                try {
                    listener.onMessage(frame);
                } catch (RuntimeException | Error e) {
                    try {
                        close();
                    } catch (RuntimeException closeFailure) {
                        e.addSuppressed(closeFailure);
                    }
                    throw e;
                }
            } else if (drain) {
                drain();
            }
        }

        private boolean enqueue(@Nullable MessagingRequestReplyMessage<JsonObject> message) {
            if (message == null) {
                if (!deliveries.isEmpty() && deliveries.getLast().message() == null) {
                    return false;
                }
                if (deliveries.size() >= MAX_PENDING_DELIVERIES) {
                    deliveries.clear();
                }
            } else if (deliveries.size() >= MAX_PENDING_DELIVERIES) {
                return false;
            }
            deliveries.add(new Delivery(message));
            if (delivering) {
                return false;
            }
            delivering = true;
            return true;
        }

        private void drain() {
            try {
                while (true) {
                    Delivery delivery;
                    synchronized (lock) {
                        delivery = deliveries.poll();
                        if (closed || delivery == null) {
                            delivering = false;
                            return;
                        }
                    }
                    if (delivery.message() == null) {
                        listener.onDisconnect();
                    } else {
                        listener.onMessage(delivery.message());
                    }
                }
            } catch (RuntimeException | Error e) {
                try {
                    close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }

        @Override
        public void close() {
            List<DDSubscription> stopped;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                deliveries.clear();
                stopped = List.copyOf(subscriptions);
                subscriptions.clear();
            }
            RuntimeException failure = null;
            for (DDSubscription subscription : stopped.reversed()) {
                try {
                    subscription.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record Delivery(@Nullable MessagingRequestReplyMessage<JsonObject> message) {
    }
}
