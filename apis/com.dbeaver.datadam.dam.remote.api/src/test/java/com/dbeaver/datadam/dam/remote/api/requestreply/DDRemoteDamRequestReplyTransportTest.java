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
package com.dbeaver.datadam.dam.remote.api.requestreply;

import com.dbeaver.datadam.dam.remote.api.exception.DDRemoteDamException;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamMessage;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSessionInfo;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamChannel;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamChannelState;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamChannelStatus;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamMessageListener;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamSessionListener;
import com.dbeaver.datadam.dam.remote.api.transport.DDRemoteDamSubscription;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyMessage;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyTransport;
import com.dbeaver.messaging.requestreply.MessagingSubscription;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Timeout(10)
class DDRemoteDamRequestReplyTransportTest {
    private static final String TOPIC = "projects";
    private static final DDRemoteDamSessionInfo SESSION = new DDRemoteDamSessionInfo("remoteDam", "run", "socket");

    @Test
    void exchangesRequestsInBothDirectionsThroughExistingMessageChannels() throws Exception {
        FakeChannel callerChannel = new FakeChannel();
        FakeChannel handlerChannel = new FakeChannel();
        callerChannel.forward = message -> handlerChannel.deliver(SESSION, message);
        handlerChannel.forward = message -> callerChannel.deliver(SESSION, message);
        JsonObject payload = new JsonObject();
        payload.addProperty("value", "request");
        try (var handler = MessagingRequestReplyEndpoint.open(DDRemoteDamRequestReplyTransport.forChannel(handlerChannel, TOPIC));
             var handlerRegistration = handler.handle(request -> {
                 request.addProperty("handled", true);
                 return request;
             });
             var caller = MessagingRequestReplyEndpoint.open(DDRemoteDamRequestReplyTransport.forChannel(callerChannel, TOPIC));
             var callerRegistration = caller.handle(request -> null)) {
            JsonObject response = caller.request(payload, Duration.ofSeconds(2));

            Assertions.assertNotNull(response);
            Assertions.assertEquals("request", response.get("value").getAsString());
            Assertions.assertTrue(response.get("handled").getAsBoolean());
            Assertions.assertFalse(payload.has("handled"));
            Assertions.assertNull(handler.request(new JsonObject(), Duration.ofSeconds(2)));
        }
        Assertions.assertFalse(callerChannel.closed);
        Assertions.assertFalse(handlerChannel.closed);
        Assertions.assertTrue(callerChannel.observers.isEmpty());
        Assertions.assertTrue(handlerChannel.observers.isEmpty());
        Assertions.assertTrue(callerChannel.subscriptions.stream().noneMatch(subscription -> subscription.active));
        Assertions.assertTrue(handlerChannel.subscriptions.stream().noneMatch(subscription -> subscription.active));
    }

    @Test
    void routesRequestsAndRepliesToUniqueEndpointTopics() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport first = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        DDRemoteDamRequestReplyTransport second = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Received firstReceived = new Received();
        Received secondReceived = new Received();
        try (var firstSubscription = first.subscribe(firstId, firstReceived);
             var secondSubscription = second.subscribe(secondId, secondReceived)) {
            Assertions.assertEquals(4, channel.subscriptions.size());
            String firstReplyTopic = channel.subscriptions.get(0).topic;
            String secondReplyTopic = channel.subscriptions.get(2).topic;
            Assertions.assertNotEquals(firstReplyTopic, secondReplyTopic);
            Assertions.assertTrue(firstReplyTopic.endsWith(firstId.toString()));
            Assertions.assertTrue(secondReplyTopic.endsWith(secondId.toString()));
            Assertions.assertEquals(TOPIC, channel.subscriptions.get(1).topic);
            Assertions.assertEquals(TOPIC, channel.subscriptions.get(3).topic);
            MessagingRequestReplyMessage<JsonObject> request = request(firstId);
            first.send(request).toCompletableFuture().join();
            DDRemoteDamMessage sent = channel.sent.getLast();
            Assertions.assertEquals(TOPIC, sent.topic());
            channel.deliver(SESSION, sent);
            Assertions.assertEquals(List.of(request), firstReceived.messages);
            Assertions.assertEquals(List.of(request), secondReceived.messages);
            second.send(request.response(null)).toCompletableFuture().join();
            Assertions.assertEquals(firstReplyTopic, channel.sent.getLast().topic());
            channel.deliver(SESSION, channel.sent.getLast());
            Assertions.assertEquals(List.of(request, request.response(null)), firstReceived.messages);
            Assertions.assertEquals(List.of(request), secondReceived.messages);
            MessagingRequestReplyMessage<JsonObject> otherRequest = request(secondId);
            first.send(otherRequest.failure(MessagingRequestReplyMessage.Error.NO_HANDLER)).toCompletableFuture().join();
            Assertions.assertEquals(secondReplyTopic, channel.sent.getLast().topic());
            channel.deliver(SESSION, channel.sent.getLast());
            Assertions.assertEquals(otherRequest.failure(MessagingRequestReplyMessage.Error.NO_HANDLER), secondReceived.messages.getLast());
        }
        Assertions.assertTrue(channel.subscriptions.stream().noneMatch(subscription -> subscription.active));
        Assertions.assertTrue(channel.observers.isEmpty());
        Assertions.assertFalse(channel.closed);
    }

    @Test
    void keepsDerivedTopicsWithinRegistrationMetadataLimit() throws Exception {
        FakeChannel channel = new FakeChannel();
        String topic = "x".repeat(256);
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, topic);
        try (var subscription = adapter.subscribe(UUID.randomUUID(), new Received())) {
            Assertions.assertEquals(topic, channel.subscriptions.get(1).topic);
            Assertions.assertTrue(channel.subscriptions.getFirst().topic.length() <= 256);
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRemoteDamRequestReplyTransport.forChannel(channel, " "));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRemoteDamRequestReplyTransport.forChannel(channel, topic + "x"));
    }

    @Test
    void rollsBackReplySubscriptionAndStateObservationWhenRequestSubscriptionFails() {
        FakeChannel channel = new FakeChannel();
        channel.failTopic = TOPIC;
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        MessagingRequestReplyException failure = Assertions.assertThrows(MessagingRequestReplyException.class,
            () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertSame(channel.subscriptionFailure, failure.getCause());
        Assertions.assertFalse(channel.subscriptions.getFirst().active);
        Assertions.assertTrue(channel.observers.isEmpty());
        Assertions.assertFalse(channel.closed);
        Assertions.assertThrows(IllegalStateException.class, () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        assertFailure(MessagingRequestReplyException.Reason.CLOSED, adapter.send(request(UUID.randomUUID())));
    }

    @Test
    void scopedSubscriberPreservesCheckedOpeningFailureAndRollsBack() {
        DDRemoteDamException unavailable = new DDRemoteDamException("Unavailable");
        AtomicInteger closed = new AtomicInteger();
        DDRemoteDamRequestReplyTransport adapter = new DDRemoteDamRequestReplyTransport(TOPIC,
            message -> CompletableFuture.completedFuture(null), (topic, listener) -> {
                if (topic.equals(TOPIC)) {
                    throw unavailable;
                }
                return closed::incrementAndGet;
            });
        MessagingRequestReplyException failure = Assertions.assertThrows(MessagingRequestReplyException.class,
            () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertSame(unavailable, failure.getCause());
        Assertions.assertEquals(1, closed.get());
    }

    @Test
    void rejectsDisconnectedAndClosedChannelsAtOpening() {
        for (DDRemoteDamChannelState state : List.of(
            DDRemoteDamChannelState.DISCONNECTED, DDRemoteDamChannelState.CONNECTING, DDRemoteDamChannelState.CLOSED
        )) {
            FakeChannel channel = new FakeChannel();
            channel.setStatus(new DDRemoteDamChannelStatus(state, null));
            DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
            MessagingRequestReplyException failure = Assertions.assertThrows(MessagingRequestReplyException.class,
                () -> adapter.subscribe(UUID.randomUUID(), new Received()));
            Assertions.assertEquals(MessagingRequestReplyException.Reason.DISCONNECTED, failure.reason());
            Assertions.assertTrue(channel.subscriptions.isEmpty());
            Assertions.assertTrue(channel.observers.isEmpty());
        }
    }

    @Test
    void rollsBackAllResourcesAfterDisconnectDuringOpeningEvenIfTheChannelReconnects() {
        FakeChannel channel = new FakeChannel();
        channel.onSubscribe = topic -> {
            if (TOPIC.equals(topic)) {
                channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.DISCONNECTED, null));
                channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, SESSION));
            }
        };
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        MessagingRequestReplyException failure = Assertions.assertThrows(MessagingRequestReplyException.class,
            () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertEquals(MessagingRequestReplyException.Reason.DISCONNECTED, failure.reason());
        Assertions.assertEquals(2, channel.subscriptions.size());
        Assertions.assertTrue(channel.subscriptions.stream().noneMatch(subscription -> subscription.active));
        Assertions.assertTrue(channel.observers.isEmpty());
    }

    @Test
    void propagatesDisconnectAndAcceptsOnlyTheCurrentPhysicalSource() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            adapter.send(request(endpoint)).toCompletableFuture().join();
            DDRemoteDamMessage wire = channel.sent.getLast();
            channel.deliver(new DDRemoteDamSessionInfo("other", "run", "socket"), wire);
            channel.deliver(new DDRemoteDamSessionInfo("remoteDam", "other", "socket"), wire);
            channel.deliver(new DDRemoteDamSessionInfo("remoteDam", "run", "other"), wire);
            Assertions.assertTrue(received.messages.isEmpty());
            channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.DISCONNECTED, null));
            Assertions.assertEquals(1, received.disconnects);
            assertFailure(MessagingRequestReplyException.Reason.DISCONNECTED, adapter.send(request(endpoint)));
            channel.deliver(SESSION, wire);
            Assertions.assertTrue(received.messages.isEmpty());
            DDRemoteDamSessionInfo replacement = new DDRemoteDamSessionInfo("remoteDam", "new-run", "new-socket");
            channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, replacement));
            channel.deliver(SESSION, wire);
            channel.deliver(replacement, wire);
            Assertions.assertEquals(1, received.messages.size());
            channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, SESSION));
            Assertions.assertEquals(2, received.disconnects);
            channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CLOSED, null));
            Assertions.assertEquals(3, received.disconnects);
        }
    }

    @Test
    void completesMessageDeliveryBeforeNotifyingAConcurrentSessionReplacement() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        CountDownLatch messageEntered = new CountDownLatch(1);
        CountDownLatch releaseMessage = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                messageEntered.countDown();
                await(releaseMessage);
                delivered.add("message");
            }

            @Override
            public void onDisconnect() {
                delivered.add("disconnect");
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();
            DDRemoteDamMessage wire = channel.sent.getLast();
            FutureTask<Void> message = async(() -> channel.deliver(SESSION, wire));
            try {
                await(messageEntered);
                CountDownLatch replacing = new CountDownLatch(1);
                DDRemoteDamSessionInfo replacement = new DDRemoteDamSessionInfo("remoteDam", "next-run", "next-socket");
                FutureTask<Void> state = async(() -> {
                    replacing.countDown();
                    channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, replacement));
                });
                try {
                    await(replacing);
                    state.get(2, TimeUnit.SECONDS);
                    Assertions.assertTrue(delivered.isEmpty());
                } finally {
                    releaseMessage.countDown();
                }
                message.get(2, TimeUnit.SECONDS);
                state.get(2, TimeUnit.SECONDS);
                Assertions.assertEquals(List.of("message", "disconnect"), delivered);

                channel.deliver(SESSION, wire);
                Assertions.assertEquals(List.of("message", "disconnect"), delivered);
            } finally {
                releaseMessage.countDown();
                message.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void notifiesSessionReplacementBeforeDeliveringANewSourceMessage() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        CountDownLatch disconnectEntered = new CountDownLatch(1);
        CountDownLatch releaseDisconnect = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                delivered.add("message");
            }

            @Override
            public void onDisconnect() {
                disconnectEntered.countDown();
                await(releaseDisconnect);
                delivered.add("disconnect");
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();
            DDRemoteDamMessage wire = channel.sent.getLast();
            DDRemoteDamSessionInfo replacement = new DDRemoteDamSessionInfo("remoteDam", "next-run", "next-socket");
            FutureTask<Void> state = async(() ->
                channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, replacement)));
            try {
                await(disconnectEntered);
                CountDownLatch receiving = new CountDownLatch(1);
                FutureTask<Void> message = async(() -> {
                    receiving.countDown();
                    channel.deliver(replacement, wire);
                });
                try {
                    await(receiving);
                    message.get(2, TimeUnit.SECONDS);
                    Assertions.assertTrue(delivered.isEmpty());
                } finally {
                    releaseDisconnect.countDown();
                }
                state.get(2, TimeUnit.SECONDS);
                message.get(2, TimeUnit.SECONDS);
                Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            } finally {
                releaseDisconnect.countDown();
                state.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void defersReentrantStateNotificationsUntilTheCurrentMessageReturns() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        List<String> delivered = new ArrayList<>();
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.DISCONNECTED, null));
                delivered.add("message");
            }

            @Override
            public void onDisconnect() {
                delivered.add("disconnect");
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();

            channel.deliver(SESSION, channel.sent.getLast());

            Assertions.assertEquals(List.of("message", "disconnect"), delivered);
        }
    }

    @Test
    void establishesTheNewGenerationBeforeMessagesWhenStateObserversAreDelayed() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        List<String> delivered = new ArrayList<>();
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                delivered.add("message");
            }

            @Override
            public void onDisconnect() {
                delivered.add("disconnect");
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();
            DDRemoteDamMessage wire = channel.sent.getLast();
            DDRemoteDamSessionInfo replacement = new DDRemoteDamSessionInfo("remoteDam", "next-run", "next-socket");
            DDRemoteDamChannelStatus ready = new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, replacement);
            channel.status = ready;

            channel.deliver(replacement, wire);

            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            for (Consumer<DDRemoteDamChannelStatus> observer : List.copyOf(channel.observers)) {
                observer.accept(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.DISCONNECTED, null));
                observer.accept(ready);
            }
            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            channel.deliver(SESSION, wire);
            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            channel.deliver(replacement, wire);
            Assertions.assertEquals(List.of("disconnect", "message", "message"), delivered);
        }
    }

    @Test
    void permitsConcurrentInlineRepliesWithoutHoldingCallbackLocks() throws Exception {
        FakeChannel leftChannel = new FakeChannel();
        FakeChannel rightChannel = new FakeChannel();
        DDRemoteDamRequestReplyTransport left = DDRemoteDamRequestReplyTransport.forChannel(leftChannel, TOPIC);
        DDRemoteDamRequestReplyTransport right = DDRemoteDamRequestReplyTransport.forChannel(rightChannel, TOPIC);
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        CountDownLatch requests = new CountDownLatch(2);
        AtomicInteger replies = new AtomicInteger();
        try (var leftSubscription = left.subscribe(leftId, inlineReplies(left, requests, replies));
             var rightSubscription = right.subscribe(rightId, inlineReplies(right, requests, replies))) {
            left.send(request(leftId)).toCompletableFuture().join();
            right.send(request(rightId)).toCompletableFuture().join();
            DDRemoteDamMessage toRight = leftChannel.sent.getLast();
            DDRemoteDamMessage toLeft = rightChannel.sent.getLast();
            leftChannel.forward = message -> rightChannel.deliver(SESSION, message);
            rightChannel.forward = message -> leftChannel.deliver(SESSION, message);

            FutureTask<Void> leftDelivery = async(() -> leftChannel.deliver(SESSION, toLeft));
            FutureTask<Void> rightDelivery = async(() -> rightChannel.deliver(SESSION, toRight));

            leftDelivery.get(2, TimeUnit.SECONDS);
            rightDelivery.get(2, TimeUnit.SECONDS);
            Assertions.assertEquals(2, replies.get());
        }
    }

    @Test
    void boundsQueuedCallbacksAndPreservesDisconnectWhenTheQueueIsFull() throws Exception {
        for (boolean disconnect : List.of(false, true)) {
            FakeChannel channel = new FakeChannel();
            DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
            UUID endpoint = UUID.randomUUID();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger messages = new AtomicInteger();
            AtomicInteger disconnects = new AtomicInteger();
            try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
                @Override
                public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                    entered.countDown();
                    await(release);
                    messages.incrementAndGet();
                }

                @Override
                public void onDisconnect() {
                    disconnects.incrementAndGet();
                }
            })) {
                adapter.send(request(endpoint)).toCompletableFuture().join();
                DDRemoteDamMessage wire = channel.sent.getLast();
                FutureTask<Void> delivery = async(() -> channel.deliver(SESSION, wire));
                try {
                    await(entered);
                    for (int i = 0; i < 130; i++) {
                        channel.deliver(SESSION, wire);
                    }
                    if (disconnect) {
                        channel.setStatus(new DDRemoteDamChannelStatus(DDRemoteDamChannelState.DISCONNECTED, null));
                    }
                } finally {
                    release.countDown();
                    delivery.get(2, TimeUnit.SECONDS);
                }
                Assertions.assertEquals(disconnect ? 1 : 129, messages.get());
                Assertions.assertEquals(disconnect ? 1 : 0, disconnects.get());
            }
        }
    }

    @Test
    void releasesReceivingResourcesWhenAListenerFails() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        IllegalStateException failure = new IllegalStateException("Listener failed");
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                throw failure;
            }

            @Override
            public void onDisconnect() {
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();

            Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class,
                () -> channel.deliver(SESSION, channel.sent.getLast())));

            assertFailure(MessagingRequestReplyException.Reason.CLOSED, adapter.send(request(endpoint)));
            Assertions.assertTrue(channel.subscriptions.stream().noneMatch(value -> value.active));
            Assertions.assertTrue(channel.observers.isEmpty());
            Assertions.assertFalse(channel.closed);
        }
    }

    @Test
    void rejectsMalformedRecognizedFramesAndWrongTypeTopicAndOrigin() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            MessagingRequestReplyMessage<JsonObject> request = request(endpoint);
            adapter.send(request).toCompletableFuture().join();
            DDRemoteDamMessage requestWire = channel.sent.getLast();
            adapter.send(request.response(new JsonObject())).toCompletableFuture().join();
            final DDRemoteDamMessage responseWire = channel.sent.getLast();
            List<JsonObject> malformed = new ArrayList<>();
            JsonObject abbreviatedId = requestWire.payload();
            abbreviatedId.addProperty("requestId", "1-1-1-1-1");
            malformed.add(abbreviatedId);
            JsonObject invalidOrigin = requestWire.payload();
            invalidOrigin.addProperty("replyTo", "not-a-uuid");
            malformed.add(invalidOrigin);
            JsonObject numericId = requestWire.payload();
            numericId.addProperty("requestId", 1);
            malformed.add(numericId);
            JsonObject unknownType = requestWire.payload();
            unknownType.addProperty("type", "OTHER");
            malformed.add(unknownType);
            JsonObject missingData = requestWire.payload();
            missingData.remove("data");
            malformed.add(missingData);
            JsonObject nullRequest = requestWire.payload();
            nullRequest.add("data", JsonNull.INSTANCE);
            malformed.add(nullRequest);
            JsonObject primitiveData = requestWire.payload();
            primitiveData.addProperty("data", "text");
            malformed.add(primitiveData);
            JsonObject requestError = requestWire.payload();
            requestError.addProperty("error", "NO_HANDLER");
            malformed.add(requestError);
            for (JsonObject envelope : malformed) {
                channel.deliver(SESSION, new DDRemoteDamMessage(TOPIC, requestWire.type(), envelope));
            }
            JsonObject responseError = responseWire.payload();
            responseError.addProperty("error", "NO_HANDLER");
            channel.deliver(SESSION, new DDRemoteDamMessage(responseWire.topic(), responseWire.type(), responseError));
            JsonObject responsePrimitive = responseWire.payload();
            responsePrimitive.addProperty("data", 42);
            channel.deliver(SESSION, new DDRemoteDamMessage(responseWire.topic(), responseWire.type(), responsePrimitive));
            JsonObject responseMissingData = responseWire.payload();
            responseMissingData.remove("data");
            channel.deliver(SESSION, new DDRemoteDamMessage(responseWire.topic(), responseWire.type(), responseMissingData));
            JsonObject wrongOrigin = responseWire.payload();
            wrongOrigin.addProperty("replyTo", UUID.randomUUID().toString());
            channel.deliver(SESSION, new DDRemoteDamMessage(responseWire.topic(), responseWire.type(), wrongOrigin));
            adapter.send(request.failure(MessagingRequestReplyMessage.Error.HANDLER_FAILED)).toCompletableFuture().join();
            DDRemoteDamMessage errorWire = channel.sent.getLast();
            JsonObject errorData = errorWire.payload();
            errorData.add("data", new JsonObject());
            channel.deliver(SESSION, new DDRemoteDamMessage(errorWire.topic(), errorWire.type(), errorData));
            JsonObject unknownError = errorWire.payload();
            unknownError.addProperty("error", "OTHER");
            channel.deliver(SESSION, new DDRemoteDamMessage(errorWire.topic(), errorWire.type(), unknownError));
            JsonObject missingError = errorWire.payload();
            missingError.remove("error");
            channel.deliver(SESSION, new DDRemoteDamMessage(errorWire.topic(), errorWire.type(), missingError));
            channel.deliver(SESSION, new DDRemoteDamMessage(TOPIC, "unrelated", requestWire.payload()));
            channel.deliver(SESSION, new DDRemoteDamMessage(TOPIC, responseWire.type(), responseWire.payload()));
            channel.deliver(SESSION, new DDRemoteDamMessage(responseWire.topic(), requestWire.type(), requestWire.payload()));
            channel.subscriptions.getFirst().listener.onMessage(SESSION, requestWire);
            channel.subscriptions.get(1).listener.onMessage(SESSION, responseWire);
            Assertions.assertTrue(received.messages.isEmpty());
            channel.deliver(SESSION, requestWire);
            channel.deliver(SESSION, responseWire);
            channel.deliver(SESSION, errorWire);
            Assertions.assertEquals(List.of(request, request.response(new JsonObject()),
                request.failure(MessagingRequestReplyMessage.Error.HANDLER_FAILED)), received.messages);
        }
    }

    @Test
    void closeIsIdempotentAndLeavesBorrowedSubscriptionsIntact() throws Exception {
        FakeChannel channel = new FakeChannel();
        final DDRemoteDamSubscription borrowed = channel.subscribe("other", (session, message) -> { });
        DDRemoteDamRequestReplyTransport adapter = DDRemoteDamRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        final MessagingSubscription subscription = adapter.subscribe(endpoint, new Received());
        Assertions.assertThrows(IllegalStateException.class, () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertThrows(CompletionException.class,
            () -> adapter.send(request(UUID.randomUUID())).toCompletableFuture().join());
        adapter.close();
        subscription.close();
        assertFailure(MessagingRequestReplyException.Reason.CLOSED, adapter.send(request(endpoint)));
        Assertions.assertTrue(channel.subscriptions.getFirst().active);
        Assertions.assertFalse(channel.closed);
        borrowed.close();
    }

    @Test
    void boundsOutstandingSendsAndReleasesBudgetOnCompletionAndFailure() throws Exception {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        DDRemoteDamRequestReplyTransport adapter = new DDRemoteDamRequestReplyTransport(TOPIC, message -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            pending.add(result);
            return result;
        }, (topic, listener) -> () -> { });
        UUID endpoint = UUID.randomUUID();
        try (var subscription = adapter.subscribe(endpoint, new Received())) {
            List<CompletionStage<Void>> sends = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                sends.add(adapter.send(request(endpoint)));
            }
            assertFailure(MessagingRequestReplyException.Reason.OVERLOADED, adapter.send(request(endpoint)));
            Assertions.assertEquals(128, pending.size());
            pending.getFirst().complete(null);
            sends.getFirst().toCompletableFuture().get(2, TimeUnit.SECONDS);
            adapter.send(request(endpoint));
            Assertions.assertEquals(129, pending.size());
            assertFailure(MessagingRequestReplyException.Reason.OVERLOADED, adapter.send(request(endpoint)));
            IllegalStateException unavailable = new IllegalStateException("Unavailable");
            pending.get(1).completeExceptionally(unavailable);
            ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                () -> sends.get(1).toCompletableFuture().get(2, TimeUnit.SECONDS));
            Assertions.assertSame(unavailable, failure.getCause());
            adapter.send(request(endpoint));
            Assertions.assertEquals(130, pending.size());
            assertFailure(MessagingRequestReplyException.Reason.OVERLOADED, adapter.send(request(endpoint)));
            Assertions.assertTrue(sends.get(2).toCompletableFuture().cancel(false));
            assertFailure(MessagingRequestReplyException.Reason.OVERLOADED, adapter.send(request(endpoint)));
            pending.get(2).complete(null);
            adapter.send(request(endpoint));
            Assertions.assertEquals(131, pending.size());
            assertFailure(MessagingRequestReplyException.Reason.OVERLOADED, adapter.send(request(endpoint)));
        } finally {
            pending.forEach(result -> result.complete(null));
        }
    }

    @Test
    void releasesSendBudgetAfterSynchronousSenderFailure() throws Exception {
        IllegalStateException unavailable = new IllegalStateException("Unavailable");
        AtomicInteger attempts = new AtomicInteger();
        DDRemoteDamRequestReplyTransport adapter = new DDRemoteDamRequestReplyTransport(TOPIC, message -> {
            if (attempts.incrementAndGet() <= 129) {
                throw unavailable;
            }
            return CompletableFuture.completedFuture(null);
        }, (topic, listener) -> () -> { });
        UUID endpoint = UUID.randomUUID();
        try (var subscription = adapter.subscribe(endpoint, new Received())) {
            for (int i = 0; i < 129; i++) {
                ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                    () -> adapter.send(request(endpoint)).toCompletableFuture().get(2, TimeUnit.SECONDS));
                Assertions.assertSame(unavailable, failure.getCause());
            }
            adapter.send(request(endpoint)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            Assertions.assertEquals(130, attempts.get());
        }
    }

    @NotNull
    private static MessagingRequestReplyMessage<JsonObject> request(@NotNull UUID endpoint) {
        return MessagingRequestReplyMessage.request(UUID.randomUUID(), endpoint, new JsonObject());
    }

    private static void assertFailure(@NotNull MessagingRequestReplyException.Reason reason, @NotNull CompletionStage<Void> result) {
        ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
        Assertions.assertEquals(reason, Assertions.assertInstanceOf(MessagingRequestReplyException.class, failure.getCause()).reason());
    }

    @NotNull
    private static FutureTask<Void> async(@NotNull Runnable action) {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Thread.ofPlatform().daemon().start(task);
        return task;
    }

    @NotNull
    private static MessagingRequestReplyTransport.Listener<JsonObject> inlineReplies(
        @NotNull DDRemoteDamRequestReplyTransport adapter,
        @NotNull CountDownLatch requests,
        @NotNull AtomicInteger replies
    ) {
        return new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                if (message.type() == MessagingRequestReplyMessage.Type.REQUEST) {
                    requests.countDown();
                    await(requests);
                    adapter.send(message.response(null)).toCompletableFuture().join();
                } else {
                    replies.incrementAndGet();
                }
            }

            @Override
            public void onDisconnect() {
            }
        };
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Received implements MessagingRequestReplyTransport.Listener<JsonObject> {
        private final List<MessagingRequestReplyMessage<JsonObject>> messages = new ArrayList<>();
        private int disconnects;

        @Override
        public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
            messages.add(message);
        }

        @Override
        public void onDisconnect() {
            disconnects++;
        }
    }

    private static final class FakeChannel implements DDRemoteDamChannel {
        private final List<TopicSubscription> subscriptions = new ArrayList<>();
        private final List<Consumer<DDRemoteDamChannelStatus>> observers = new ArrayList<>();
        private final List<DDRemoteDamMessage> sent = new ArrayList<>();
        private final RuntimeException subscriptionFailure = new IllegalStateException("Subscription unavailable");
        private volatile DDRemoteDamChannelStatus status = new DDRemoteDamChannelStatus(DDRemoteDamChannelState.CONNECTED, SESSION);
        @Nullable
        private String failTopic;
        @Nullable
        private Consumer<String> onSubscribe;
        @Nullable
        private Consumer<DDRemoteDamMessage> forward;
        private boolean closed;

        @Override
        @NotNull
        public DDRemoteDamChannelStatus getStatus() {
            return status;
        }

        @Override
        @NotNull
        public DDRemoteDamSubscription observeState(@NotNull Consumer<DDRemoteDamChannelStatus> observer) {
            observers.add(observer);
            observer.accept(status);
            return () -> observers.remove(observer);
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull DDRemoteDamMessage message) {
            sent.add(message);
            if (forward != null) {
                forward.accept(message);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @NotNull
        public DDRemoteDamSubscription subscribe(@NotNull String topic, @NotNull DDRemoteDamMessageListener listener) {
            if (topic.equals(failTopic)) {
                throw subscriptionFailure;
            }
            TopicSubscription subscription = new TopicSubscription(topic, listener);
            subscriptions.add(subscription);
            if (onSubscribe != null) {
                onSubscribe.accept(topic);
            }
            return () -> subscription.active = false;
        }

        @Override
        @NotNull
        public DDRemoteDamSubscription addSessionListener(@NotNull DDRemoteDamSessionListener listener) {
            throw new UnsupportedOperationException();
        }

        private void setStatus(@NotNull DDRemoteDamChannelStatus next) {
            status = next;
            List.copyOf(observers).forEach(observer -> observer.accept(next));
        }

        private void deliver(@NotNull DDRemoteDamSessionInfo source, @NotNull DDRemoteDamMessage message) {
            for (TopicSubscription subscription : List.copyOf(subscriptions)) {
                if (subscription.active && subscription.topic.equals(message.topic())) {
                    subscription.listener.onMessage(source, message);
                }
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TopicSubscription {
        private final String topic;
        private final DDRemoteDamMessageListener listener;
        private boolean active = true;

        private TopicSubscription(@NotNull String topic, @NotNull DDRemoteDamMessageListener listener) {
            this.topic = topic;
            this.listener = listener;
        }
    }
}
