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
package com.dbeaver.datadam.messaging.api.rpc;

import com.dbeaver.datadam.messaging.api.model.DDDamConnectionInfo;
import com.dbeaver.datadam.messaging.api.model.DDMessagingMessage;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingChannel;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingChannelState;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingChannelStatus;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingConnectionListener;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingMessageListener;
import com.dbeaver.datadam.messaging.api.transport.DDMessagingSubscription;
import com.dbeaver.messaging.requestreply.MessagingRequestReplyOptions;
import com.dbeaver.messaging.rpc.MessagingRpcCodec;
import com.dbeaver.messaging.rpc.MessagingRpcEndpoint;
import com.dbeaver.messaging.rpc.MessagingRpcException;
import com.dbeaver.messaging.rpc.MessagingRpcMessage;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Timeout(10)
class DDMessagingRpcTopicsIntegrationTest {
    private static final DDDamConnectionInfo CONNECTION = new DDDamConnectionInfo("desktop", "run", "physical-connection");
    private static final MessagingRequestReplyOptions OPTIONS = new MessagingRequestReplyOptions(Duration.ofSeconds(2), 16, 8);

    @Test
    void routesExactDeveloperTopicsBidirectionallyWithIndependentBindingCodecs() {
        Channel desktop = new Channel();
        Channel firstBackend = new Channel();
        Channel secondBackend = new Channel();
        desktop.forward = message -> {
            firstBackend.receive(message);
            secondBackend.receive(message);
        };
        firstBackend.forward = desktop::receive;
        secondBackend.forward = desktop::receive;
        var codec = new DDMessagingGsonRpcCodec(new Gson());
        String firstTopic = "hello.files.v2";
        String secondTopic = "developer..chosen-topic";
        var serviceCodec = new PrefixCodec("response:", "argument:");
        var firstProxyCodec = new PrefixCodec("first:", "one:");
        var secondProxyCodec = new PrefixCodec("second:", "two:");
        try (var desktopRpc = MessagingRpcEndpoint.open(DDMessagingRpcTransport.forChannel(desktop), codec, OPTIONS);
             var firstRpc = MessagingRpcEndpoint.open(DDMessagingRpcTransport.forChannel(firstBackend), OPTIONS);
             var secondRpc = MessagingRpcEndpoint.open(DDMessagingRpcTransport.forChannel(secondBackend), OPTIONS);
             var firstGreeting = firstRpc.register(firstTopic, Greeting.class, value -> value, serviceCodec);
             var secondGreeting = secondRpc.register(secondTopic, Greeting.class, value -> "other:" + value, codec);
             var reverseGreeting = desktopRpc.register(firstTopic, Greeting.class, value -> "desktop:" + value)) {
            Greeting first = desktopRpc.proxy(firstTopic, Greeting.class, firstProxyCodec);
            Greeting second = desktopRpc.proxy(firstTopic, Greeting.class, secondProxyCodec);
            Assertions.assertEquals("one:response:argument:first:Hello", first.hello("Hello"));
            Assertions.assertEquals("two:response:argument:second:Hello", second.hello("Hello"));
            Assertions.assertEquals("other:Hello", desktopRpc.proxy(secondTopic, Greeting.class).hello("Hello"));
            Assertions.assertEquals("desktop:reverse", firstRpc.proxy(firstTopic, Greeting.class, codec).hello("reverse"));
            Assertions.assertEquals("response:argument:fallback", desktopRpc.proxy(firstTopic, Greeting.class).hello("fallback"));
            Assertions.assertEquals(4, desktop.subscriptions.size());
            Assertions.assertEquals(2, firstBackend.subscriptions.size());
            Assertions.assertEquals(2, secondBackend.subscriptions.size());
            Assertions.assertTrue(desktop.subscriptions.stream().anyMatch(value -> value.topic().equals(firstTopic)));
            Assertions.assertTrue(desktop.subscriptions.stream().anyMatch(value -> value.topic().equals(secondTopic)));
            for (DDMessagingMessage message : desktop.sent) {
                if ("REQUEST".equals(message.payload().get("type").getAsString())) {
                    Assertions.assertTrue(List.of(firstTopic, secondTopic).contains(message.topic()));
                    JsonElement envelope = message.payload().get("data");
                    Assertions.assertFalse(envelope.getAsJsonObject().has("topic"));
                    Assertions.assertEquals("dbeaver.rpc.v2", envelope.getAsJsonObject().get("protocol").getAsString());
                    Assertions.assertEquals(1, envelope.getAsJsonObject().getAsJsonArray("data").size());
                }
            }
            firstGreeting.close();
            MessagingRpcException missing = Assertions.assertThrows(MessagingRpcException.class,
                () -> first.hello("closed"));
            Assertions.assertEquals(MessagingRpcMessage.Error.UNKNOWN_SERVICE, missing.remoteError());
            Assertions.assertEquals("other:still", desktopRpc.proxy(secondTopic, Greeting.class).hello("still"));
        }
        for (Channel channel : List.of(desktop, firstBackend, secondBackend)) {
            Assertions.assertFalse(channel.closed);
            Assertions.assertTrue(channel.subscriptions.isEmpty());
            Assertions.assertTrue(channel.observers.isEmpty());
        }
    }

    /** Answers a greeting on the selected backend. */
    public interface Greeting {
        @NotNull
        String hello(@NotNull String value);
    }

    private record PrefixCodec(@NotNull String encodedPrefix, @NotNull String decodedPrefix) implements MessagingRpcCodec<JsonElement> {
        @Override
        @NotNull
        public JsonElement encode(@Nullable Object value, @NotNull Type type) {
            return new JsonPrimitive(encodedPrefix + value);
        }

        @Override
        @NotNull
        public Object decode(@Nullable JsonElement payload, @NotNull Type type) {
            return decodedPrefix + payload.getAsString();
        }
    }

    private static final class Channel implements DDMessagingChannel {
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final List<Consumer<DDMessagingChannelStatus>> observers = new CopyOnWriteArrayList<>();
        private final List<DDMessagingMessage> sent = new CopyOnWriteArrayList<>();
        private Consumer<DDMessagingMessage> forward = message -> { };
        private boolean closed;

        @Override
        @NotNull
        public DDMessagingChannelStatus getStatus() {
            return new DDMessagingChannelStatus(
                closed ? DDMessagingChannelState.CLOSED : DDMessagingChannelState.CONNECTED, closed ? null : CONNECTION);
        }

        @Override
        @NotNull
        public DDMessagingSubscription observeState(@NotNull Consumer<DDMessagingChannelStatus> observer) {
            observers.add(observer);
            observer.accept(getStatus());
            return () -> observers.remove(observer);
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull DDMessagingMessage message) {
            sent.add(message);
            forward.accept(message);
            return CompletableFuture.completedFuture(null);
        }

        private void receive(@NotNull DDMessagingMessage message) {
            for (Subscription subscription : subscriptions) {
                if (subscription.topic.equals(message.topic())) {
                    subscription.listener.onMessage(CONNECTION, message);
                }
            }
        }

        @Override
        @NotNull
        public DDMessagingSubscription subscribe(@NotNull String topic, @NotNull DDMessagingMessageListener listener) {
            var subscription = new Subscription(topic, listener);
            subscriptions.add(subscription);
            return () -> subscriptions.remove(subscription);
        }

        @Override
        @NotNull
        public DDMessagingSubscription addConnectionListener(@NotNull DDMessagingConnectionListener listener) {
            return () -> { };
        }

        @Override
        public void close() {
            closed = true;
            observers.forEach(observer -> observer.accept(getStatus()));
            subscriptions.clear();
            observers.clear();
        }
    }

    private record Subscription(@NotNull String topic, @NotNull DDMessagingMessageListener listener) {
    }
}
