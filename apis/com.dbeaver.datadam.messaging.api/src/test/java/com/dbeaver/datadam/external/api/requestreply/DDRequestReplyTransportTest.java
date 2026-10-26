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
import com.dbeaver.datadam.external.api.transport.DDChannelState;
import com.dbeaver.datadam.external.api.transport.DDChannelStatus;
import com.dbeaver.datadam.external.api.transport.DDConnectionListener;
import com.dbeaver.datadam.external.api.transport.DDMessageChannel;
import com.dbeaver.datadam.external.api.transport.DDMessageListener;
import com.dbeaver.datadam.external.api.transport.DDSubscription;
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
class DDRequestReplyTransportTest {
    private static final String TOPIC = "projects";
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("desktop", "run", "socket");

    @Test
    void exchangesRequestsInBothDirectionsThroughExistingMessageChannels() throws Exception {
        FakeChannel callerChannel = new FakeChannel();
        FakeChannel handlerChannel = new FakeChannel();
        callerChannel.forward = message -> handlerChannel.deliver(CONNECTION, message);
        handlerChannel.forward = message -> callerChannel.deliver(CONNECTION, message);
        JsonObject payload = new JsonObject();
        payload.addProperty("value", "request");
        try (var handler = MessagingRequestReplyEndpoint.open(DDRequestReplyTransport.forChannel(handlerChannel, TOPIC));
             var handlerRegistration = handler.handle(request -> {
                 request.addProperty("handled", true);
                 return request;
             });
             var caller = MessagingRequestReplyEndpoint.open(DDRequestReplyTransport.forChannel(callerChannel, TOPIC));
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
        DDRequestReplyTransport first = DDRequestReplyTransport.forChannel(channel, TOPIC);
        DDRequestReplyTransport second = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
            DDMessage sent = channel.sent.getLast();
            Assertions.assertEquals(TOPIC, sent.topic());
            channel.deliver(CONNECTION, sent);
            Assertions.assertEquals(List.of(request), firstReceived.messages);
            Assertions.assertEquals(List.of(request), secondReceived.messages);
            second.send(request.response(null)).toCompletableFuture().join();
            Assertions.assertEquals(firstReplyTopic, channel.sent.getLast().topic());
            channel.deliver(CONNECTION, channel.sent.getLast());
            Assertions.assertEquals(List.of(request, request.response(null)), firstReceived.messages);
            Assertions.assertEquals(List.of(request), secondReceived.messages);
            MessagingRequestReplyMessage<JsonObject> otherRequest = request(secondId);
            first.send(otherRequest.failure(MessagingRequestReplyMessage.Error.NO_HANDLER)).toCompletableFuture().join();
            Assertions.assertEquals(secondReplyTopic, channel.sent.getLast().topic());
            channel.deliver(CONNECTION, channel.sent.getLast());
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
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, topic);
        try (var subscription = adapter.subscribe(UUID.randomUUID(), new Received())) {
            Assertions.assertEquals(topic, channel.subscriptions.get(1).topic);
            Assertions.assertTrue(channel.subscriptions.getFirst().topic.length() <= 256);
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRequestReplyTransport.forChannel(channel, " "));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRequestReplyTransport.forChannel(channel, topic + "x"));
    }

    @Test
    void rollsBackReplySubscriptionAndStateObservationWhenRequestSubscriptionFails() {
        FakeChannel channel = new FakeChannel();
        channel.failTopic = TOPIC;
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
        DDExternalDamException unavailable = new DDExternalDamException("Unavailable");
        AtomicInteger closed = new AtomicInteger();
        DDRequestReplyTransport adapter = new DDRequestReplyTransport(TOPIC,
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
        for (DDChannelState state : List.of(DDChannelState.DISCONNECTED, DDChannelState.CONNECTING, DDChannelState.CLOSED)) {
            FakeChannel channel = new FakeChannel();
            channel.setStatus(new DDChannelStatus(state, null));
            DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
                channel.setStatus(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
                channel.setStatus(new DDChannelStatus(DDChannelState.CONNECTED, CONNECTION));
            }
        };
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            adapter.send(request(endpoint)).toCompletableFuture().join();
            DDMessage wire = channel.sent.getLast();
            channel.deliver(new DDConnectionInfo("other", "run", "socket"), wire);
            channel.deliver(new DDConnectionInfo("desktop", "other", "socket"), wire);
            channel.deliver(new DDConnectionInfo("desktop", "run", "other"), wire);
            Assertions.assertTrue(received.messages.isEmpty());
            channel.setStatus(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
            Assertions.assertEquals(1, received.disconnects);
            assertFailure(MessagingRequestReplyException.Reason.DISCONNECTED, adapter.send(request(endpoint)));
            channel.deliver(CONNECTION, wire);
            Assertions.assertTrue(received.messages.isEmpty());
            DDConnectionInfo replacement = new DDConnectionInfo("desktop", "new-run", "new-socket");
            channel.setStatus(new DDChannelStatus(DDChannelState.CONNECTED, replacement));
            channel.deliver(CONNECTION, wire);
            channel.deliver(replacement, wire);
            Assertions.assertEquals(1, received.messages.size());
            channel.setStatus(new DDChannelStatus(DDChannelState.CONNECTED, CONNECTION));
            Assertions.assertEquals(2, received.disconnects);
            channel.setStatus(new DDChannelStatus(DDChannelState.CLOSED, null));
            Assertions.assertEquals(3, received.disconnects);
        }
    }

    @Test
    void completesMessageDeliveryBeforeNotifyingAConcurrentConnectionReplacement() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
            DDMessage wire = channel.sent.getLast();
            FutureTask<Void> message = async(() -> channel.deliver(CONNECTION, wire));
            try {
                await(messageEntered);
                CountDownLatch replacing = new CountDownLatch(1);
                DDConnectionInfo replacement = new DDConnectionInfo("desktop", "next-run", "next-socket");
                FutureTask<Void> state = async(() -> {
                    replacing.countDown();
                    channel.setStatus(new DDChannelStatus(DDChannelState.CONNECTED, replacement));
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

                channel.deliver(CONNECTION, wire);
                Assertions.assertEquals(List.of("message", "disconnect"), delivered);
            } finally {
                releaseMessage.countDown();
                message.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void notifiesConnectionReplacementBeforeDeliveringANewSourceMessage() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
            DDMessage wire = channel.sent.getLast();
            DDConnectionInfo replacement = new DDConnectionInfo("desktop", "next-run", "next-socket");
            FutureTask<Void> state = async(() -> channel.setStatus(new DDChannelStatus(DDChannelState.CONNECTED, replacement)));
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
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        List<String> delivered = new ArrayList<>();
        try (var subscription = adapter.subscribe(endpoint, new MessagingRequestReplyTransport.Listener<>() {
            @Override
            public void onMessage(@NotNull MessagingRequestReplyMessage<JsonObject> message) {
                channel.setStatus(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
                delivered.add("message");
            }

            @Override
            public void onDisconnect() {
                delivered.add("disconnect");
            }
        })) {
            adapter.send(request(endpoint)).toCompletableFuture().join();

            channel.deliver(CONNECTION, channel.sent.getLast());

            Assertions.assertEquals(List.of("message", "disconnect"), delivered);
        }
    }

    @Test
    void establishesTheNewGenerationBeforeMessagesWhenStateObserversAreDelayed() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
            DDMessage wire = channel.sent.getLast();
            DDConnectionInfo replacement = new DDConnectionInfo("desktop", "next-run", "next-socket");
            DDChannelStatus ready = new DDChannelStatus(DDChannelState.CONNECTED, replacement);
            channel.status = ready;

            channel.deliver(replacement, wire);

            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            for (Consumer<DDChannelStatus> observer : List.copyOf(channel.observers)) {
                observer.accept(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
                observer.accept(ready);
            }
            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            channel.deliver(CONNECTION, wire);
            Assertions.assertEquals(List.of("disconnect", "message"), delivered);
            channel.deliver(replacement, wire);
            Assertions.assertEquals(List.of("disconnect", "message", "message"), delivered);
        }
    }

    @Test
    void permitsConcurrentInlineRepliesWithoutHoldingCallbackLocks() throws Exception {
        FakeChannel leftChannel = new FakeChannel();
        FakeChannel rightChannel = new FakeChannel();
        DDRequestReplyTransport left = DDRequestReplyTransport.forChannel(leftChannel, TOPIC);
        DDRequestReplyTransport right = DDRequestReplyTransport.forChannel(rightChannel, TOPIC);
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        CountDownLatch requests = new CountDownLatch(2);
        AtomicInteger replies = new AtomicInteger();
        try (var leftSubscription = left.subscribe(leftId, inlineReplies(left, requests, replies));
             var rightSubscription = right.subscribe(rightId, inlineReplies(right, requests, replies))) {
            left.send(request(leftId)).toCompletableFuture().join();
            right.send(request(rightId)).toCompletableFuture().join();
            DDMessage toRight = leftChannel.sent.getLast();
            DDMessage toLeft = rightChannel.sent.getLast();
            leftChannel.forward = message -> rightChannel.deliver(CONNECTION, message);
            rightChannel.forward = message -> leftChannel.deliver(CONNECTION, message);

            FutureTask<Void> leftDelivery = async(() -> leftChannel.deliver(CONNECTION, toLeft));
            FutureTask<Void> rightDelivery = async(() -> rightChannel.deliver(CONNECTION, toRight));

            leftDelivery.get(2, TimeUnit.SECONDS);
            rightDelivery.get(2, TimeUnit.SECONDS);
            Assertions.assertEquals(2, replies.get());
        }
    }

    @Test
    void boundsQueuedCallbacksAndPreservesDisconnectWhenTheQueueIsFull() throws Exception {
        for (boolean disconnect : List.of(false, true)) {
            FakeChannel channel = new FakeChannel();
            DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
                DDMessage wire = channel.sent.getLast();
                FutureTask<Void> delivery = async(() -> channel.deliver(CONNECTION, wire));
                try {
                    await(entered);
                    for (int i = 0; i < 130; i++) {
                        channel.deliver(CONNECTION, wire);
                    }
                    if (disconnect) {
                        channel.setStatus(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
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
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
                () -> channel.deliver(CONNECTION, channel.sent.getLast())));

            assertFailure(MessagingRequestReplyException.Reason.CLOSED, adapter.send(request(endpoint)));
            Assertions.assertTrue(channel.subscriptions.stream().noneMatch(value -> value.active));
            Assertions.assertTrue(channel.observers.isEmpty());
            Assertions.assertFalse(channel.closed);
        }
    }

    @Test
    void rejectsMalformedRecognizedFramesAndWrongTypeTopicAndOrigin() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            MessagingRequestReplyMessage<JsonObject> request = request(endpoint);
            adapter.send(request).toCompletableFuture().join();
            DDMessage requestWire = channel.sent.getLast();
            adapter.send(request.response(new JsonObject())).toCompletableFuture().join();
            final DDMessage responseWire = channel.sent.getLast();
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
                channel.deliver(CONNECTION, new DDMessage(TOPIC, requestWire.type(), envelope));
            }
            JsonObject responseError = responseWire.payload();
            responseError.addProperty("error", "NO_HANDLER");
            channel.deliver(CONNECTION, new DDMessage(responseWire.topic(), responseWire.type(), responseError));
            JsonObject responsePrimitive = responseWire.payload();
            responsePrimitive.addProperty("data", 42);
            channel.deliver(CONNECTION, new DDMessage(responseWire.topic(), responseWire.type(), responsePrimitive));
            JsonObject responseMissingData = responseWire.payload();
            responseMissingData.remove("data");
            channel.deliver(CONNECTION, new DDMessage(responseWire.topic(), responseWire.type(), responseMissingData));
            JsonObject wrongOrigin = responseWire.payload();
            wrongOrigin.addProperty("replyTo", UUID.randomUUID().toString());
            channel.deliver(CONNECTION, new DDMessage(responseWire.topic(), responseWire.type(), wrongOrigin));
            adapter.send(request.failure(MessagingRequestReplyMessage.Error.HANDLER_FAILED)).toCompletableFuture().join();
            DDMessage errorWire = channel.sent.getLast();
            JsonObject errorData = errorWire.payload();
            errorData.add("data", new JsonObject());
            channel.deliver(CONNECTION, new DDMessage(errorWire.topic(), errorWire.type(), errorData));
            JsonObject unknownError = errorWire.payload();
            unknownError.addProperty("error", "OTHER");
            channel.deliver(CONNECTION, new DDMessage(errorWire.topic(), errorWire.type(), unknownError));
            JsonObject missingError = errorWire.payload();
            missingError.remove("error");
            channel.deliver(CONNECTION, new DDMessage(errorWire.topic(), errorWire.type(), missingError));
            channel.deliver(CONNECTION, new DDMessage(TOPIC, "unrelated", requestWire.payload()));
            channel.deliver(CONNECTION, new DDMessage(TOPIC, responseWire.type(), responseWire.payload()));
            channel.deliver(CONNECTION, new DDMessage(responseWire.topic(), requestWire.type(), requestWire.payload()));
            channel.subscriptions.getFirst().listener.onMessage(CONNECTION, requestWire);
            channel.subscriptions.get(1).listener.onMessage(CONNECTION, responseWire);
            Assertions.assertTrue(received.messages.isEmpty());
            channel.deliver(CONNECTION, requestWire);
            channel.deliver(CONNECTION, responseWire);
            channel.deliver(CONNECTION, errorWire);
            Assertions.assertEquals(List.of(request, request.response(new JsonObject()),
                request.failure(MessagingRequestReplyMessage.Error.HANDLER_FAILED)), received.messages);
        }
    }

    @Test
    void closeIsIdempotentAndLeavesBorrowedSubscriptionsIntact() throws Exception {
        FakeChannel channel = new FakeChannel();
        final DDSubscription borrowed = channel.subscribe("other", (connection, message) -> { });
        DDRequestReplyTransport adapter = DDRequestReplyTransport.forChannel(channel, TOPIC);
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
        DDRequestReplyTransport adapter = new DDRequestReplyTransport(TOPIC, message -> {
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
        DDRequestReplyTransport adapter = new DDRequestReplyTransport(TOPIC, message -> {
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
        @NotNull DDRequestReplyTransport adapter,
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

    private static final class FakeChannel implements DDMessageChannel {
        private final List<TopicSubscription> subscriptions = new ArrayList<>();
        private final List<Consumer<DDChannelStatus>> observers = new ArrayList<>();
        private final List<DDMessage> sent = new ArrayList<>();
        private final RuntimeException subscriptionFailure = new IllegalStateException("Subscription unavailable");
        private volatile DDChannelStatus status = new DDChannelStatus(DDChannelState.CONNECTED, CONNECTION);
        @Nullable
        private String failTopic;
        @Nullable
        private Consumer<String> onSubscribe;
        @Nullable
        private Consumer<DDMessage> forward;
        private boolean closed;

        @Override
        @NotNull
        public DDChannelStatus getStatus() {
            return status;
        }

        @Override
        @NotNull
        public DDSubscription observeState(@NotNull Consumer<DDChannelStatus> observer) {
            observers.add(observer);
            observer.accept(status);
            return () -> observers.remove(observer);
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull DDMessage message) {
            sent.add(message);
            if (forward != null) {
                forward.accept(message);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @NotNull
        public DDSubscription subscribe(@NotNull String topic, @NotNull DDMessageListener listener) {
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
        public DDSubscription addConnectionListener(@NotNull DDConnectionListener listener) {
            throw new UnsupportedOperationException();
        }

        private void setStatus(@NotNull DDChannelStatus next) {
            status = next;
            List.copyOf(observers).forEach(observer -> observer.accept(next));
        }

        private void deliver(@NotNull DDConnectionInfo source, @NotNull DDMessage message) {
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
        private final DDMessageListener listener;
        private boolean active = true;

        private TopicSubscription(@NotNull String topic, @NotNull DDMessageListener listener) {
            this.topic = topic;
            this.listener = listener;
        }
    }
}
