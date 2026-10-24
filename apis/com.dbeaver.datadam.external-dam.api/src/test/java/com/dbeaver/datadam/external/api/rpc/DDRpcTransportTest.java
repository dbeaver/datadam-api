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

import com.dbeaver.datadam.external.api.model.DDConnectionInfo;
import com.dbeaver.datadam.external.api.model.DDMessage;
import com.dbeaver.datadam.external.api.requestreply.DDRequestReplyTransport;
import com.dbeaver.datadam.external.api.transport.DDChannelState;
import com.dbeaver.datadam.external.api.transport.DDChannelStatus;
import com.dbeaver.datadam.external.api.transport.DDConnectionListener;
import com.dbeaver.datadam.external.api.transport.DDMessageChannel;
import com.dbeaver.datadam.external.api.transport.DDMessageListener;
import com.dbeaver.datadam.external.api.transport.DDSubscription;
import com.dbeaver.messaging.requestreply.MessageSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.RequestReplyException;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import com.dbeaver.messaging.rpc.RpcCodec;
import com.dbeaver.messaging.rpc.RpcEndpoint;
import com.dbeaver.messaging.rpc.RpcException;
import com.dbeaver.messaging.rpc.RpcMessage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Timeout(10)
public class DDRpcTransportTest {
    private static final String TOPIC = "custom.files";
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("desktop", "run", "socket");
    private static final RequestReplyOptions OPTIONS = new RequestReplyOptions(Duration.ofSeconds(2), 16, 8);
    private static final RpcCodec<JsonElement> CODEC = new GsonCodec();

    @Test
    void delegatesRequestExecutionUnlessAnExplicitExecutorIsSupplied() {
        MemoryTransport delegate = new MemoryTransport();
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        delegate.requestExecutor = queued::add;
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        adapter.executeRequest(calls::incrementAndGet);
        Assertions.assertEquals(1, queued.size());
        Assertions.assertEquals(0, calls.get());
        queued.removeFirst().run();
        Assertions.assertEquals(1, calls.get());

        RejectedExecutionException unavailable = new RejectedExecutionException("Request execution unavailable");
        delegate.requestExecutor = request -> {
            throw unavailable;
        };
        Assertions.assertSame(unavailable,
            Assertions.assertThrows(RejectedExecutionException.class, () -> adapter.executeRequest(calls::incrementAndGet)));

        DDRpcTransport inline = new DDRpcTransport(delegate, Runnable::run);
        inline.executeRequest(calls::incrementAndGet);
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void channelRequestsRunInlineByDefaultAndCanUseABorrowedExecutor() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            for (boolean supplied : List.of(false, true)) {
                FakeChannel channel = new FakeChannel();
                channel.forward = channel::deliver;
                AtomicReference<Thread> handlerThread = new AtomicReference<>();
                try (var endpoint = RpcEndpoint.open(topic -> supplied
                    ? DDRpcTransport.forChannel(channel, topic, executor)
                    : DDRpcTransport.forChannel(channel, topic), CODEC, OPTIONS);
                     var service = endpoint.register(TOPIC, Text.class, value -> {
                         handlerThread.set(Thread.currentThread());
                         return "reply:" + value;
                     })) {
                    Assertions.assertEquals("reply:value", endpoint.proxy(TOPIC, Text.class).apply("value"));
                    if (supplied) {
                        Assertions.assertNotEquals(Thread.currentThread(), handlerThread.get());
                    } else {
                        Assertions.assertSame(Thread.currentThread(), handlerThread.get());
                    }
                }
                Assertions.assertFalse(executor.isShutdown());
                Assertions.assertEquals("still available", executor.submit(() -> "still available").get(2, TimeUnit.SECONDS));
                Assertions.assertFalse(channel.closed);
            }
        }
    }

    @Test
    void mapsSuppliedExecutorRejectionToRemoteOverloadAndAllowsFreshCalls() {
        FakeChannel channel = new FakeChannel();
        channel.forward = channel::deliver;
        AtomicBoolean rejecting = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        Executor executor = request -> {
            if (rejecting.get()) {
                throw new RejectedExecutionException("Request execution unavailable");
            }
            request.run();
        };
        var options = new RequestReplyOptions(Duration.ofSeconds(2), 1, 1);
        try (var endpoint = RpcEndpoint.open(topic -> DDRpcTransport.forChannel(channel, topic, executor), CODEC, options);
             var service = endpoint.register(TOPIC, Text.class, value -> {
                 calls.incrementAndGet();
                 return value;
             })) {
            Text proxy = endpoint.proxy(TOPIC, Text.class);
            RpcException failure = Assertions.assertThrows(RpcException.class, () -> proxy.apply("rejected"));
            Assertions.assertEquals(RpcException.Reason.REMOTE_ERROR, failure.reason());
            Assertions.assertNull(failure.remoteError());
            RequestReplyException outer = Assertions.assertInstanceOf(RequestReplyException.class, failure.getCause());
            Assertions.assertEquals(RequestReplyMessage.Error.OVERLOADED, outer.remoteError());
            Assertions.assertEquals(0, calls.get());

            rejecting.set(false);
            Assertions.assertEquals("recovered", proxy.apply("recovered"));
            Assertions.assertEquals(1, calls.get());
        }
    }

    @Test
    void supportsSynchronousCallbacksWithBorrowedRequestExecutorsAndIndependentChannelDelivery() throws Exception {
        try (var deliveries = Executors.newCachedThreadPool();
             var requests = Executors.newCachedThreadPool()) {
            FakeChannel left = new FakeChannel();
            FakeChannel right = new FakeChannel();
            left.forward = message -> deliveries.execute(() -> right.deliver(message));
            right.forward = message -> deliveries.execute(() -> left.deliver(message));
            try (var caller = RpcEndpoint.open(topic -> DDRpcTransport.forChannel(left, topic, requests), CODEC, OPTIONS);
                 var handler = RpcEndpoint.open(topic -> DDRpcTransport.forChannel(right, topic, requests), CODEC, OPTIONS);
                 var callback = caller.register(TOPIC, Text.class, value -> "callback:" + value);
                 var service = handler.register(TOPIC, Text.class, value -> handler.proxy(TOPIC, Text.class).apply(value))) {
                Assertions.assertEquals("callback:value", caller.proxy(TOPIC, Text.class).apply("value"));
            }
            Assertions.assertFalse(requests.isShutdown());
            Assertions.assertFalse(deliveries.isShutdown());
            Assertions.assertFalse(left.closed);
            Assertions.assertFalse(right.closed);
        }
    }

    @Test
    void servesIndependentBidirectionalTopicsWithoutClosingTheBorrowedChannel() throws Exception {
        FakeChannel left = new FakeChannel();
        FakeChannel right = new FakeChannel();
        left.forward = right::deliver;
        right.forward = left::deliver;
        AtomicInteger nullArguments = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        final DDSubscription borrowed = left.subscribe("other", (connection, message) -> { });
        try (var caller = RpcEndpoint.open(topic -> new DDRpcTransport(DDRequestReplyTransport.forChannel(left, topic)), CODEC, OPTIONS);
             var handler = RpcEndpoint.open(DDRpcTransport.forChannel(right), CODEC, OPTIONS);
             var leftNames = caller.register("names", Names.class, values -> List.of("left", values.getFirst()));
             var rightNames = handler.register("names", Names.class, values -> List.of("right", values.getFirst()));
             var otherNames = handler.register("other-names", Names.class, values -> List.of("other"));
             var records = handler.register("records", Records.class, value -> new Item(value.name(), List.of("record")));
             var nullable = handler.register("nullable", Text.class, value -> {
                 if (value == null) {
                     nullArguments.incrementAndGet();
                 }
                 return null;
             });
             var notification = handler.register("notifications", Notification.class, value -> notifications.incrementAndGet())) {
            Assertions.assertEquals(List.of("right", "request"), caller.proxy("names", Names.class).apply(List.of("request")));
            Assertions.assertEquals(List.of("left", "reverse"), handler.proxy("names", Names.class).apply(List.of("reverse")));
            Assertions.assertEquals(List.of("other"), caller.proxy("other-names", Names.class).apply(List.of()));
            Assertions.assertEquals(new Item("name", List.of("record")),
                caller.proxy("records", Records.class).apply(new Item("name", List.of("argument"))));
            Assertions.assertNull(caller.proxy("nullable", Text.class).apply(null));
            Assertions.assertEquals(1, nullArguments.get());
            caller.proxy("notifications", Notification.class).accept(null);
            Assertions.assertEquals(1, notifications.get());
            Assertions.assertEquals(11, left.subscriptions.size());
            Assertions.assertEquals(10, right.subscriptions.size());
            Assertions.assertEquals("notifications", left.subscriptions.getLast().topic);
            Assertions.assertEquals("notifications", right.subscriptions.getLast().topic);
            otherNames.close();
            RpcException missing = Assertions.assertThrows(RpcException.class,
                () -> caller.proxy("other-names", Names.class).apply(List.of()));
            Assertions.assertEquals(RpcMessage.Error.UNKNOWN_SERVICE, missing.remoteError());
            Assertions.assertEquals(List.of("right", "still-open"),
                caller.proxy("names", Names.class).apply(List.of("still-open")));
        }
        Assertions.assertFalse(left.closed);
        Assertions.assertFalse(right.closed);
        Assertions.assertTrue(left.observers.isEmpty());
        Assertions.assertTrue(right.observers.isEmpty());
        Assertions.assertTrue(left.subscriptions.getFirst().active);
        Assertions.assertEquals(1, left.subscriptions.stream().filter(subscription -> subscription.active).count());
        Assertions.assertTrue(right.subscriptions.stream().noneMatch(subscription -> subscription.active));
        borrowed.close();
    }

    @Test
    void isolatesGsonArgumentsAndResultsAcrossRealMessageChannels() throws Exception {
        FakeChannel left = new FakeChannel();
        FakeChannel right = new FakeChannel();
        left.forward = right::deliver;
        right.forward = left::deliver;
        AtomicReference<Item> received = new AtomicReference<>();
        Item argument = new Item("argument", new ArrayList<>(List.of("initial")));
        try (var caller = RpcEndpoint.open(DDRpcTransport.forChannel(left), CODEC, OPTIONS);
             var handler = RpcEndpoint.open(DDRpcTransport.forChannel(right), CODEC, OPTIONS);
             var service = handler.register("records", Records.class, value -> {
                 received.set(value);
                 value.tags().add("handler");
                 return value;
             })) {
            Item result = caller.proxy("records", Records.class).apply(argument);
            Assertions.assertEquals(List.of("initial"), argument.tags());
            Assertions.assertEquals(List.of("initial", "handler"), result.tags());
            argument.tags().add("caller");
            Assertions.assertEquals(List.of("initial", "handler"), received.get().tags());
            received.get().tags().add("later");
            Assertions.assertEquals(List.of("initial", "handler"), result.tags());
            result.tags().add("result");
            Assertions.assertEquals(List.of("initial", "handler", "later"), received.get().tags());
        }
    }

    @Test
    void preservesCorrelationOuterTypesAndBothErrorNamespaces() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        RequestReplyMessage<RpcMessage<JsonElement>> request = request(endpoint, new JsonPrimitive("argument"));
        try (var subscription = adapter.subscribe(endpoint, received)) {
            Assertions.assertEquals(endpoint, delegate.endpoint);
            adapter.send(request).toCompletableFuture().join();
            RequestReplyMessage<JsonObject> sent = delegate.sent.getLast();
            Assertions.assertEquals(request.requestId(), sent.requestId());
            Assertions.assertEquals(request.replyTo(), sent.replyTo());
            Assertions.assertEquals(RequestReplyMessage.Type.REQUEST, sent.type());
            Assertions.assertEquals("dbeaver.rpc.v2", Objects.requireNonNull(sent.payload()).get("protocol").getAsString());
            Assertions.assertFalse(sent.payload().has("topic"));
            delegate.deliver(sent);
            Assertions.assertEquals(request, received.messages.getLast());
            RequestReplyMessage<RpcMessage<JsonElement>> response = request.response(RpcMessage.response(new JsonPrimitive(42)));
            adapter.send(response).toCompletableFuture().join();
            delegate.deliver(delegate.sent.getLast());
            Assertions.assertEquals(response, received.messages.getLast());
            for (RpcMessage.Error error : RpcMessage.Error.values()) {
                RequestReplyMessage<RpcMessage<JsonElement>> failure = request.response(RpcMessage.failure(error));
                adapter.send(failure).toCompletableFuture().join();
                RequestReplyMessage<JsonObject> wire = delegate.sent.getLast();
                Assertions.assertEquals(request.requestId(), wire.requestId());
                Assertions.assertEquals(request.replyTo(), wire.replyTo());
                Assertions.assertEquals(RequestReplyMessage.Type.RESPONSE, wire.type());
                Assertions.assertNull(wire.error());
                Assertions.assertFalse(Objects.requireNonNull(wire.payload()).has("data"));
                Assertions.assertEquals(error.name(), wire.payload().get("error").getAsString());
                delegate.deliver(wire);
                Assertions.assertEquals(failure, received.messages.getLast());
            }
            for (RequestReplyMessage.Error error : RequestReplyMessage.Error.values()) {
                RequestReplyMessage<RpcMessage<JsonElement>> failure = request.failure(error);
                adapter.send(failure).toCompletableFuture().join();
                RequestReplyMessage<JsonObject> wire = delegate.sent.getLast();
                Assertions.assertNull(wire.payload());
                Assertions.assertEquals(error, wire.error());
                delegate.deliver(wire);
                Assertions.assertEquals(failure, received.messages.getLast());
            }
            adapter.send(request.response(null)).toCompletableFuture().join();
            delegate.deliver(delegate.sent.getLast());
            Assertions.assertEquals(request.response(null), received.messages.getLast());
        }
        Assertions.assertTrue(delegate.closed);
    }

    @Test
    void acceptsArbitraryJsonValuesAndNormalizesEncodedNull() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        List<JsonElement> values = List.of(JsonParser.parseString("{\"items\":[1,true,null]}"),
            JsonParser.parseString("[\"a\",2,null]"), new JsonPrimitive("text"), new JsonPrimitive(42),
            new JsonPrimitive(true), JsonNull.INSTANCE);
        try (var subscription = adapter.subscribe(endpoint, received)) {
            for (JsonElement value : values) {
                RequestReplyMessage<RpcMessage<JsonElement>> request = request(endpoint, value);
                adapter.send(request).toCompletableFuture().join();
                RequestReplyMessage<JsonObject> wire = delegate.sent.getLast();
                Assertions.assertEquals(value, Objects.requireNonNull(wire.payload()).get("data"));
                delegate.deliver(wire);
                Assertions.assertEquals(value.isJsonNull() ? null : value,
                    Objects.requireNonNull(received.messages.getLast().payload()).payload());
                adapter.send(request.response(RpcMessage.response(value))).toCompletableFuture().join();
                delegate.deliver(delegate.sent.getLast());
                Assertions.assertEquals(RpcMessage.Type.RESPONSE, Objects.requireNonNull(received.messages.getLast().payload()).type());
                Assertions.assertEquals(value.isJsonNull() ? null : value, received.messages.getLast().payload().payload());
            }
        }
    }

    @Test
    void isolatesSnapshotsEvenWhenTheBorrowedDelegateDoesNotCopy() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            for (RequestReplyMessage.Type type : List.of(RequestReplyMessage.Type.REQUEST, RequestReplyMessage.Type.RESPONSE)) {
                JsonObject argument = JsonParser.parseString("{\"items\":[\"original\"]}").getAsJsonObject();
                RequestReplyMessage<RpcMessage<JsonElement>> request = request(endpoint, argument);
                adapter.send(type == RequestReplyMessage.Type.REQUEST ? request : request.response(RpcMessage.response(argument)));
                JsonObject wire = Objects.requireNonNull(delegate.sent.getLast().payload());
                argument.getAsJsonArray("items").add("caller");
                Assertions.assertEquals(1, wire.getAsJsonObject("data").getAsJsonArray("items").size());
                delegate.deliver(delegate.sent.getLast());
                JsonObject decoded = Objects.requireNonNull(Objects.requireNonNull(received.messages.getLast().payload()).payload())
                    .getAsJsonObject();
                wire.getAsJsonObject("data").getAsJsonArray("items").add("delegate");
                Assertions.assertEquals(1, decoded.getAsJsonArray("items").size());
                decoded.getAsJsonArray("items").add("listener");
                Assertions.assertEquals("delegate", wire.getAsJsonObject("data").getAsJsonArray("items").get(1).getAsString());
                Assertions.assertEquals("caller", argument.getAsJsonArray("items").get(1).getAsString());
            }
        }
    }

    @Test
    void dropsMalformedVersionsStringFieldsNamesAndRequestShapes() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        Received received = new Received();
        UUID endpoint = UUID.randomUUID();
        RequestReplyMessage<JsonObject> request = RequestReplyMessage.request(UUID.randomUUID(), endpoint, requestEnvelope());
        List<JsonElement> nonStrings = List.of(new JsonPrimitive(1), new JsonPrimitive(true), JsonNull.INSTANCE,
            new JsonObject(), new JsonArray());
        try (var subscription = adapter.subscribe(endpoint, received)) {
            for (String field : List.of("protocol", "type", "operation")) {
                JsonObject missing = requestEnvelope();
                missing.remove(field);
                delegate.deliver(RequestReplyMessage.request(request.requestId(), endpoint, missing));
                for (JsonElement value : nonStrings) {
                    JsonObject invalid = requestEnvelope();
                    invalid.add(field, value);
                    delegate.deliver(RequestReplyMessage.request(request.requestId(), endpoint, invalid));
                }
            }
            List<Consumer<JsonObject>> malformed = List.of(
                envelope -> envelope.addProperty("protocol", "dbeaver.rpc.v1"),
                envelope -> envelope.addProperty("protocol", "unrelated"),
                envelope -> envelope.addProperty("type", "OTHER"),
                envelope -> envelope.addProperty("type", "request"),
                envelope -> envelope.addProperty("type", "RESPONSE"),
                envelope -> envelope.addProperty("type", "ERROR"),
                envelope -> envelope.addProperty("topic", "legacy.topic"),
                envelope -> envelope.addProperty("operation", "\t"),
                envelope -> envelope.addProperty("operation", "x".repeat(1025)),
                envelope -> envelope.remove("data"),
                envelope -> envelope.add("error", JsonNull.INSTANCE),
                envelope -> envelope.addProperty("extra", true)
            );
            for (Consumer<JsonObject> mutation : malformed) {
                JsonObject invalid = requestEnvelope();
                mutation.accept(invalid);
                delegate.deliver(RequestReplyMessage.request(request.requestId(), endpoint, invalid));
            }
            Assertions.assertTrue(received.messages.isEmpty());
            JsonObject boundary = requestEnvelope();
            boundary.addProperty("operation", "x".repeat(1024));
            delegate.deliver(RequestReplyMessage.request(request.requestId(), endpoint, boundary));
            Assertions.assertEquals(1, received.messages.size());
            RpcMessage<JsonElement> accepted = Objects.requireNonNull(received.messages.getLast().payload());
            Assertions.assertEquals("x".repeat(1024), accepted.operation());
            Assertions.assertNull(accepted.payload());
        }
    }

    @Test
    void dropsMalformedResponseAndNestedErrorShapes() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        Received received = new Received();
        UUID endpoint = UUID.randomUUID();
        RequestReplyMessage<JsonObject> request = RequestReplyMessage.request(UUID.randomUUID(), endpoint, requestEnvelope());
        try (var subscription = adapter.subscribe(endpoint, received)) {
            List<Consumer<JsonObject>> malformedResponses = List.of(
                envelope -> envelope.remove("data"),
                envelope -> envelope.add("topic", JsonNull.INSTANCE),
                envelope -> envelope.addProperty("operation", "apply"),
                envelope -> envelope.add("error", JsonNull.INSTANCE),
                envelope -> envelope.addProperty("protocol", 1),
                envelope -> envelope.addProperty("type", true),
                envelope -> envelope.addProperty("type", "OTHER")
            );
            for (Consumer<JsonObject> mutation : malformedResponses) {
                JsonObject invalid = responseEnvelope();
                mutation.accept(invalid);
                delegate.deliver(request.response(invalid));
            }
            delegate.deliver(request.response(requestEnvelope()));
            List<Consumer<JsonObject>> malformedErrors = List.of(
                envelope -> envelope.remove("error"),
                envelope -> envelope.addProperty("error", "OTHER"),
                envelope -> envelope.addProperty("error", 1),
                envelope -> envelope.addProperty("error", true),
                envelope -> envelope.add("error", JsonNull.INSTANCE),
                envelope -> envelope.add("error", new JsonObject()),
                envelope -> envelope.add("data", JsonNull.INSTANCE),
                envelope -> envelope.addProperty("topic", "names"),
                envelope -> envelope.add("operation", JsonNull.INSTANCE)
            );
            for (Consumer<JsonObject> mutation : malformedErrors) {
                JsonObject invalid = errorEnvelope(RpcMessage.Error.HANDLER_FAILED);
                mutation.accept(invalid);
                delegate.deliver(request.response(invalid));
            }
            Assertions.assertTrue(received.messages.isEmpty());
            delegate.deliver(request.response(responseEnvelope()));
            delegate.deliver(request.response(errorEnvelope(RpcMessage.Error.HANDLER_FAILED)));
            Assertions.assertEquals(2, received.messages.size());
        }
    }

    @Test
    void requiresObjectEnvelopesThroughExistingDataDamFraming() throws Exception {
        FakeChannel channel = new FakeChannel();
        DDRpcTransport adapter = DDRpcTransport.forChannel(channel, TOPIC);
        UUID endpoint = UUID.randomUUID();
        Received received = new Received();
        try (var subscription = adapter.subscribe(endpoint, received)) {
            adapter.send(request(endpoint, null)).toCompletableFuture().join();
            DDMessage valid = channel.sent.getLast();
            for (JsonElement value : List.of(new JsonPrimitive("text"), new JsonArray(), JsonNull.INSTANCE)) {
                JsonObject invalid = valid.payload();
                invalid.add("data", value);
                channel.deliver(new DDMessage(valid.topic(), valid.type(), invalid));
            }
            Assertions.assertTrue(received.messages.isEmpty());
            channel.deliver(valid);
            Assertions.assertEquals(1, received.messages.size());
        }
    }

    @Test
    void neutralRpcDistinguishesNestedErrorsOuterErrorsAndInvalidResponses() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        try (var endpoint = RpcEndpoint.open(topic -> new DDRpcTransport(delegate), CODEC, OPTIONS)) {
            Text proxy = endpoint.proxy("text", Text.class);
            for (RpcMessage.Error error : RpcMessage.Error.values()) {
                delegate.forward = request -> delegate.deliver(request.response(errorEnvelope(error)));
                RpcException failure = Assertions.assertThrows(RpcException.class, () -> proxy.apply("request"));
                Assertions.assertEquals(RpcException.Reason.REMOTE_ERROR, failure.reason());
                Assertions.assertEquals(error, failure.remoteError());
                Assertions.assertNull(failure.getCause());
            }
            for (RequestReplyMessage.Error error : RequestReplyMessage.Error.values()) {
                delegate.forward = request -> delegate.deliver(request.failure(error));
                RpcException failure = Assertions.assertThrows(RpcException.class, () -> proxy.apply("request"));
                Assertions.assertEquals(RpcException.Reason.REMOTE_ERROR, failure.reason());
                Assertions.assertNull(failure.remoteError());
                RequestReplyException outer = Assertions.assertInstanceOf(RequestReplyException.class, failure.getCause());
                Assertions.assertEquals(error, outer.remoteError());
            }
            delegate.forward = request -> delegate.deliver(request.response(null));
            RpcException invalid = Assertions.assertThrows(RpcException.class, () -> proxy.apply("request"));
            Assertions.assertEquals(RpcException.Reason.INVALID_RESPONSE, invalid.reason());
        }
    }

    @Test
    void malformedAndUnknownVersionRepliesRemainUnansweredUntilTimeout() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        RequestReplyOptions options = new RequestReplyOptions(Duration.ofMillis(100), 4, 2);
        try (var endpoint = RpcEndpoint.open(topic -> new DDRpcTransport(delegate), CODEC, options)) {
            for (Consumer<JsonObject> mutation : List.<Consumer<JsonObject>>of(
                envelope -> envelope.addProperty("protocol", "dbeaver.rpc.v1"),
                envelope -> envelope.addProperty("type", "UNKNOWN"),
                envelope -> envelope.remove("data")
            )) {
                JsonObject invalid = responseEnvelope();
                mutation.accept(invalid);
                delegate.forward = request -> delegate.deliver(request.response(invalid));
                RpcException failure = Assertions.assertThrows(RpcException.class,
                    () -> endpoint.proxy("text", Text.class).apply("request"));
                Assertions.assertEquals(RpcException.Reason.TIMEOUT, failure.reason());
            }
            Assertions.assertEquals(3, delegate.sent.size());
        }
    }

    @Test
    void dispatchesRealRpcFailuresInsideSuccessfulRequestReplyResponses() throws Exception {
        FakeChannel left = new FakeChannel();
        FakeChannel right = new FakeChannel();
        left.forward = right::deliver;
        right.forward = left::deliver;
        try (var caller = RequestReplyEndpoint.open(DDRpcTransport.forChannel(left, TOPIC), OPTIONS);
             var missingCaller = RequestReplyEndpoint.open(DDRpcTransport.forChannel(left, "missing"), OPTIONS);
             var handler = RpcEndpoint.open(DDRpcTransport.forChannel(right), CODEC, OPTIONS);
             var service = handler.register(TOPIC, Text.class, value -> {
                 throw new IllegalStateException("Handler failed");
             })) {
            handler.proxy("missing", Text.class);
            RpcMessage<JsonElement> missingService = missingCaller.request(RpcMessage.request("apply", null));
            Assertions.assertEquals(RpcMessage.failure(RpcMessage.Error.UNKNOWN_SERVICE), missingService);
            RpcMessage<JsonElement> missingOperation = caller.request(RpcMessage.request("missing", null));
            Assertions.assertEquals(RpcMessage.failure(RpcMessage.Error.UNKNOWN_OPERATION), missingOperation);
            String operation = "apply(" + String.class.getTypeName() + ")";
            RpcMessage<JsonElement> codecFailure = caller.request(RpcMessage.request(operation, new JsonObject()));
            Assertions.assertEquals(RpcMessage.failure(RpcMessage.Error.CODEC_FAILED), codecFailure);
            RpcMessage<JsonElement> handlerFailure = caller.request(RpcMessage.request(operation, new JsonPrimitive("value")));
            Assertions.assertEquals(RpcMessage.failure(RpcMessage.Error.HANDLER_FAILED), handlerFailure);
            Assertions.assertTrue(right.sent.stream().allMatch(message ->
                "RESPONSE".equals(message.payload().get("type").getAsString())));
        }
    }

    @Test
    void rejectsMismatchedOutgoingFramesBeforeCallingTheDelegate() {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        UUID endpoint = UUID.randomUUID();
        RequestReplyMessage<RpcMessage<JsonElement>> request = request(endpoint, null);
        List<RequestReplyMessage<RpcMessage<JsonElement>>> malformed = List.of(
            RequestReplyMessage.request(request.requestId(), endpoint, RpcMessage.response(null)),
            RequestReplyMessage.request(request.requestId(), endpoint, RpcMessage.failure(RpcMessage.Error.INVALID_REQUEST)),
            request.response(request.payload())
        );
        for (RequestReplyMessage<RpcMessage<JsonElement>> message : malformed) {
            ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                () -> adapter.send(message).toCompletableFuture().get(2, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        Assertions.assertTrue(delegate.sent.isEmpty());
    }

    @Test
    void preservesAsynchronousAndSynchronousSendFailuresWithoutRetriesOrWrapping() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        RequestReplyMessage<RpcMessage<JsonElement>> request = request(UUID.randomUUID(), null);
        CompletableFuture<Void> pending = new CompletableFuture<>();
        delegate.sendResult = pending;
        CompletionStage<Void> pendingResult = adapter.send(request);
        Assertions.assertFalse(pendingResult.toCompletableFuture().isDone());
        pending.complete(null);
        Assertions.assertNull(pendingResult.toCompletableFuture().get(2, TimeUnit.SECONDS));
        for (RequestReplyException.Reason reason : List.of(RequestReplyException.Reason.DISCONNECTED,
            RequestReplyException.Reason.CLOSED, RequestReplyException.Reason.OVERLOADED, RequestReplyException.Reason.SEND_FAILED)) {
            RequestReplyException unavailable = new RequestReplyException(reason);
            delegate.sendResult = CompletableFuture.failedFuture(unavailable);
            CompletionStage<Void> result = adapter.send(request);
            ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
                () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
            Assertions.assertSame(unavailable, failure.getCause());
        }
        IllegalStateException unavailable = new IllegalStateException("Unavailable");
        delegate.sendFailure = unavailable;
        for (int i = 0; i < 129; i++) {
            CompletionStage<Void> result = Assertions.assertDoesNotThrow(() -> adapter.send(request));
            assertSendFailure(unavailable, result);
        }
        delegate.sendFailure = null;
        delegate.sendResult = CompletableFuture.completedFuture(null);
        adapter.send(request).toCompletableFuture().get(2, TimeUnit.SECONDS);
        Assertions.assertEquals(135, delegate.sent.size());
    }

    @Test
    void boundsFramingBeforeCopyingAndHoldsPermitsUntilActualSendCompletion() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        AtomicInteger copies = new AtomicInteger();
        JsonElement value = new JsonElement() {
            @Override
            @NotNull
            public JsonElement deepCopy() {
                copies.incrementAndGet();
                return new JsonPrimitive("snapshot");
            }
        };
        RequestReplyMessage<RpcMessage<JsonElement>> request = request(UUID.randomUUID(), value);
        RequestReplyMessage<RpcMessage<JsonElement>> response = request.response(RpcMessage.response(value));
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<CompletableFuture<Void>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 128; i++) {
                CompletableFuture<Void> send = new CompletableFuture<>();
                pending.add(send);
                delegate.sendResult = send;
                results.add(adapter.send(response).toCompletableFuture());
            }
            Assertions.assertEquals(128, delegate.sent.size());
            Assertions.assertEquals(128, copies.get());
            Assertions.assertTrue(results.stream().noneMatch(CompletableFuture::isDone));
            assertOverloaded(adapter.send(response));
            assertOverloaded(adapter.send(request));
            assertOverloaded(adapter.send(request.failure(RequestReplyMessage.Error.OVERLOADED)));
            Assertions.assertEquals(128, delegate.sent.size());
            Assertions.assertEquals(128, copies.get());

            pending.getFirst().complete(null);
            Assertions.assertNull(results.getFirst().get(2, TimeUnit.SECONDS));
            CompletableFuture<Void> afterCompletion = new CompletableFuture<>();
            pending.add(afterCompletion);
            delegate.sendResult = afterCompletion;
            results.add(adapter.send(request).toCompletableFuture());
            Assertions.assertEquals(129, delegate.sent.size());
            assertOverloaded(adapter.send(response));

            RequestReplyException unavailable = new RequestReplyException(RequestReplyException.Reason.DISCONNECTED);
            pending.get(1).completeExceptionally(unavailable);
            assertSendFailure(unavailable, results.get(1));
            CompletableFuture<Void> afterFailure = new CompletableFuture<>();
            pending.add(afterFailure);
            delegate.sendResult = afterFailure;
            results.add(adapter.send(response).toCompletableFuture());
            Assertions.assertEquals(130, delegate.sent.size());
            assertOverloaded(adapter.send(response));

            Assertions.assertTrue(results.get(2).cancel(false));
            Assertions.assertFalse(pending.get(2).isDone());
            assertOverloaded(adapter.send(response));
            Assertions.assertEquals(130, delegate.sent.size());
            Assertions.assertEquals(130, copies.get());
            pending.get(2).complete(null);
            Assertions.assertTrue(results.get(2).isCancelled());
            CompletableFuture<Void> afterCancellation = new CompletableFuture<>();
            pending.add(afterCancellation);
            delegate.sendResult = afterCancellation;
            results.add(adapter.send(response).toCompletableFuture());
            Assertions.assertEquals(131, delegate.sent.size());
            Assertions.assertEquals(131, copies.get());
            assertOverloaded(adapter.send(response));
        } finally {
            pending.forEach(send -> send.complete(null));
        }
    }

    @Test
    void releasesPermitsAfterSnapshotFramingFailures() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        IllegalStateException unavailable = new IllegalStateException("Snapshot unavailable");
        JsonElement value = new JsonElement() {
            @Override
            @NotNull
            public JsonElement deepCopy() {
                throw unavailable;
            }
        };
        UUID endpoint = UUID.randomUUID();
        RequestReplyMessage<RpcMessage<JsonElement>> request = request(endpoint, value);
        for (int i = 0; i < 129; i++) {
            CompletionStage<Void> result = Assertions.assertDoesNotThrow(() -> adapter.send(request));
            assertSendFailure(unavailable, result);
        }
        Assertions.assertTrue(delegate.sent.isEmpty());
        adapter.send(request(endpoint, new JsonPrimitive("recovered"))).toCompletableFuture().get(2, TimeUnit.SECONDS);
        Assertions.assertEquals(1, delegate.sent.size());
    }

    @Test
    void forwardsDisconnectsAndDelegatesSubscriptionOwnership() throws Exception {
        MemoryTransport delegate = new MemoryTransport();
        DDRpcTransport adapter = new DDRpcTransport(delegate);
        Received received = new Received();
        MessageSubscription subscription = adapter.subscribe(UUID.randomUUID(), received);
        Assertions.assertSame(delegate.subscription, subscription);
        Objects.requireNonNull(delegate.listener).onDisconnect();
        Assertions.assertEquals(1, received.disconnects);
        subscription.close();
        Assertions.assertTrue(delegate.closed);
        RequestReplyException unavailable = new RequestReplyException(RequestReplyException.Reason.DISCONNECTED);
        MemoryTransport failing = new MemoryTransport();
        failing.subscribeFailure = unavailable;
        RequestReplyException failure = Assertions.assertThrows(RequestReplyException.class,
            () -> new DDRpcTransport(failing).subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertSame(unavailable, failure);
    }

    @Test
    void rollsBackDelegatedChannelSubscriptionsWithoutClosingTheBorrowedChannel() {
        FakeChannel channel = new FakeChannel();
        channel.failTopic = TOPIC;
        DDRpcTransport adapter = DDRpcTransport.forChannel(channel, TOPIC);
        RequestReplyException failure = Assertions.assertThrows(RequestReplyException.class,
            () -> adapter.subscribe(UUID.randomUUID(), new Received()));
        Assertions.assertSame(channel.subscriptionFailure, failure.getCause());
        Assertions.assertEquals(1, channel.subscriptions.size());
        Assertions.assertFalse(channel.subscriptions.getFirst().active);
        Assertions.assertTrue(channel.observers.isEmpty());
        Assertions.assertFalse(channel.closed);
        Assertions.assertThrows(IllegalStateException.class, () -> adapter.subscribe(UUID.randomUUID(), new Received()));
    }

    @Test
    void failedLazyTopicSubscriptionRollsBackAndCanBeRetried() {
        FakeChannel channel = new FakeChannel();
        channel.failTopic = TOPIC;
        try (var endpoint = RpcEndpoint.open(DDRpcTransport.forChannel(channel), CODEC, OPTIONS)) {
            RpcException failure = Assertions.assertThrows(RpcException.class, () -> endpoint.proxy(TOPIC, Text.class));
            Assertions.assertEquals(RpcException.Reason.SEND_FAILED, failure.reason());
            Assertions.assertSame(channel.subscriptionFailure, failure.getCause().getCause());
            Assertions.assertTrue(channel.observers.isEmpty());
            Assertions.assertTrue(channel.subscriptions.stream().noneMatch(subscription -> subscription.active));
            channel.failTopic = null;
            endpoint.proxy(TOPIC, Text.class);
            Assertions.assertEquals(2, channel.subscriptions.stream().filter(subscription -> subscription.active).count());
        }
        Assertions.assertFalse(channel.closed);
        Assertions.assertTrue(channel.observers.isEmpty());
        Assertions.assertTrue(channel.subscriptions.stream().noneMatch(subscription -> subscription.active));
    }

    @Test
    void incompatibleCodecPayloadsUseNormalSendAndRemoteCodecFailures() {
        FakeChannel left = new FakeChannel();
        FakeChannel right = new FakeChannel();
        left.forward = right::deliver;
        right.forward = left::deliver;
        RpcCodec<String> incompatible = new RpcCodec<>() {
            @Override
            @Nullable
            public String encode(@Nullable Object value, @NotNull Type type) {
                return String.class.cast(value);
            }

            @Override
            @Nullable
            public Object decode(@Nullable String payload, @NotNull Type type) {
                return payload;
            }
        };
        try (var caller = RpcEndpoint.open(DDRpcTransport.forChannel(left), CODEC, OPTIONS);
             var handler = RpcEndpoint.open(DDRpcTransport.forChannel(right), CODEC, OPTIONS);
             var service = handler.register(TOPIC, Text.class, value -> value)) {
            RpcException sendFailure = Assertions.assertThrows(RpcException.class,
                () -> caller.proxy(TOPIC, Text.class, incompatible).apply("incompatible"));
            Assertions.assertEquals(RpcException.Reason.SEND_FAILED, sendFailure.reason());
            Assertions.assertEquals("compatible", caller.proxy(TOPIC, Text.class).apply("compatible"));
            service.close();
            try (var replacement = handler.register(TOPIC, Text.class, value -> value, incompatible)) {
                RpcException codecFailure = Assertions.assertThrows(RpcException.class,
                    () -> caller.proxy(TOPIC, Text.class).apply("incompatible"));
                Assertions.assertEquals(RpcException.Reason.REMOTE_ERROR, codecFailure.reason());
                Assertions.assertEquals(RpcMessage.Error.CODEC_FAILED, codecFailure.remoteError());
            }
        }
    }

    @Test
    void channelDisconnectTerminatesPendingRpcAndClosingLeavesTheChannelIntact() throws Exception {
        FakeChannel channel = new FakeChannel();
        channel.forward = message -> channel.setStatus(new DDChannelStatus(DDChannelState.DISCONNECTED, null));
        try (var endpoint = RpcEndpoint.open(DDRpcTransport.forChannel(channel), CODEC, OPTIONS)) {
            RpcException failure = Assertions.assertThrows(RpcException.class,
                () -> endpoint.proxy("text", Text.class).apply("request"));
            Assertions.assertEquals(RpcException.Reason.DISCONNECTED, failure.reason());
        }
        Assertions.assertFalse(channel.closed);
        Assertions.assertTrue(channel.observers.isEmpty());
        Assertions.assertTrue(channel.subscriptions.stream().noneMatch(subscription -> subscription.active));
    }

    @NotNull
    private static RequestReplyMessage<RpcMessage<JsonElement>> request(@NotNull UUID endpoint, @Nullable JsonElement value) {
        return RequestReplyMessage.request(UUID.randomUUID(), endpoint, RpcMessage.request("apply", value));
    }

    private static void assertSendFailure(@NotNull Throwable expected, @NotNull CompletionStage<Void> result) {
        ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
        Assertions.assertSame(expected, failure.getCause());
    }

    private static void assertOverloaded(@NotNull CompletionStage<Void> result) {
        ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
            () -> result.toCompletableFuture().get(2, TimeUnit.SECONDS));
        RequestReplyException unavailable = Assertions.assertInstanceOf(RequestReplyException.class, failure.getCause());
        Assertions.assertEquals(RequestReplyException.Reason.OVERLOADED, unavailable.reason());
    }

    @NotNull
    private static JsonObject requestEnvelope() {
        return JsonParser.parseString("{\"protocol\":\"dbeaver.rpc.v2\",\"type\":\"REQUEST\","
            + "\"operation\":\"apply\",\"data\":null}").getAsJsonObject();
    }

    @NotNull
    private static JsonObject responseEnvelope() {
        return JsonParser.parseString("{\"protocol\":\"dbeaver.rpc.v2\",\"type\":\"RESPONSE\",\"data\":null}").getAsJsonObject();
    }

    @NotNull
    private static JsonObject errorEnvelope(@NotNull RpcMessage.Error error) {
        JsonObject result = JsonParser.parseString("{\"protocol\":\"dbeaver.rpc.v2\",\"type\":\"ERROR\"}").getAsJsonObject();
        result.addProperty("error", error.name());
        return result;
    }

    /** Exchanges concretely typed generic values. */
    public interface Names {
        @NotNull
        List<String> apply(@NotNull List<String> values);
    }

    /** Exchanges application records. */
    public interface Records {
        @NotNull
        Item apply(@NotNull Item value);
    }

    /** Exchanges nullable application values. */
    public interface Text {
        @Nullable
        String apply(@Nullable String value);
    }

    /** Completes calls without an application result. */
    public interface Notification {
        void accept(@Nullable String value);
    }

    public record Item(@NotNull String name, @NotNull List<String> tags) {
    }

    private static final class GsonCodec implements RpcCodec<JsonElement> {
        private final Gson gson = new Gson();

        @Override
        @Nullable
        public JsonElement encode(@Nullable Object value, @NotNull Type type) {
            JsonElement encoded = gson.toJsonTree(value, type);
            return encoded.isJsonNull() ? null : encoded.deepCopy();
        }

        @Override
        @Nullable
        public Object decode(@Nullable JsonElement payload, @NotNull Type type) {
            return gson.fromJson(payload == null ? JsonNull.INSTANCE : payload.deepCopy(), type);
        }
    }

    private static final class Received implements RequestReplyTransport.Listener<RpcMessage<JsonElement>> {
        private final List<RequestReplyMessage<RpcMessage<JsonElement>>> messages = new ArrayList<>();
        private int disconnects;

        @Override
        public void onMessage(@NotNull RequestReplyMessage<RpcMessage<JsonElement>> message) {
            messages.add(message);
        }

        @Override
        public void onDisconnect() {
            disconnects++;
        }
    }

    private static final class MemoryTransport implements RequestReplyTransport<JsonObject> {
        private final List<RequestReplyMessage<JsonObject>> sent = new ArrayList<>();
        private final MessageSubscription subscription = () -> this.closed = true;
        private CompletionStage<Void> sendResult = CompletableFuture.completedFuture(null);
        private Executor requestExecutor = Runnable::run;
        @Nullable
        private UUID endpoint;
        @Nullable
        private Listener<JsonObject> listener;
        @Nullable
        private Consumer<RequestReplyMessage<JsonObject>> forward;
        @Nullable
        private RequestReplyException subscribeFailure;
        @Nullable
        private RuntimeException sendFailure;
        private boolean closed;

        @Override
        @NotNull
        public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<JsonObject> receiver)
                throws RequestReplyException {
            if (subscribeFailure != null) {
                throw subscribeFailure;
            }
            endpoint = endpointId;
            listener = receiver;
            return subscription;
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull RequestReplyMessage<JsonObject> message) {
            sent.add(message);
            if (sendFailure != null) {
                throw sendFailure;
            }
            if (forward != null) {
                forward.accept(message);
            }
            return sendResult;
        }

        @Override
        public void executeRequest(@NotNull Runnable request) {
            requestExecutor.execute(request);
        }

        private void deliver(@NotNull RequestReplyMessage<JsonObject> message) {
            Objects.requireNonNull(listener).onMessage(message);
        }
    }

    private static final class FakeChannel implements DDMessageChannel {
        private final List<TopicSubscription> subscriptions = new CopyOnWriteArrayList<>();
        private final List<Consumer<DDChannelStatus>> observers = new CopyOnWriteArrayList<>();
        private final List<DDMessage> sent = new CopyOnWriteArrayList<>();
        private final RuntimeException subscriptionFailure = new IllegalStateException("Subscription unavailable");
        private DDChannelStatus status = new DDChannelStatus(DDChannelState.CONNECTED, CONNECTION);
        @Nullable
        private String failTopic;
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
            return () -> subscription.active = false;
        }

        @Override
        @NotNull
        public DDSubscription addConnectionListener(@NotNull DDConnectionListener listener) {
            throw new UnsupportedOperationException();
        }

        private void deliver(@NotNull DDMessage message) {
            for (TopicSubscription subscription : subscriptions) {
                if (subscription.active && subscription.topic.equals(message.topic())) {
                    subscription.listener.onMessage(CONNECTION, message);
                }
            }
        }

        private void setStatus(@NotNull DDChannelStatus next) {
            status = next;
            observers.forEach(observer -> observer.accept(next));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TopicSubscription {
        private final String topic;
        private final DDMessageListener listener;
        private volatile boolean active = true;

        private TopicSubscription(@NotNull String topic, @NotNull DDMessageListener listener) {
            this.topic = topic;
            this.listener = listener;
        }
    }
}
