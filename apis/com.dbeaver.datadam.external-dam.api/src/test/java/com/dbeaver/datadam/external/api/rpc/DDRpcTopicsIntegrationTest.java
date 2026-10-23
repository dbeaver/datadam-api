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
import com.dbeaver.datadam.external.api.transport.DDChannelState;
import com.dbeaver.datadam.external.api.transport.DDChannelStatus;
import com.dbeaver.datadam.external.api.transport.DDConnectionListener;
import com.dbeaver.datadam.external.api.transport.DDMessageChannel;
import com.dbeaver.datadam.external.api.transport.DDMessageListener;
import com.dbeaver.datadam.external.api.transport.DDSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import com.dbeaver.messaging.rpc.RpcCodec;
import com.dbeaver.messaging.rpc.RpcEndpoint;
import com.dbeaver.messaging.rpc.RpcException;
import com.dbeaver.messaging.rpc.RpcMessage;
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
class DDRpcTopicsIntegrationTest {
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("desktop", "run", "physical-connection");
    private static final RequestReplyOptions OPTIONS = new RequestReplyOptions(Duration.ofSeconds(2), 16, 8);

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
        var codec = new DDGsonRpcCodec(new Gson());
        String firstTopic = "hello.files.v2";
        String secondTopic = "developer..chosen-topic";
        var serviceCodec = new PrefixCodec("response:", "argument:");
        var firstProxyCodec = new PrefixCodec("first:", "one:");
        var secondProxyCodec = new PrefixCodec("second:", "two:");
        try (var desktopRpc = RpcEndpoint.open(DDRpcTransport.forChannel(desktop), codec, OPTIONS);
             var firstRpc = RpcEndpoint.open(DDRpcTransport.forChannel(firstBackend), OPTIONS);
             var secondRpc = RpcEndpoint.open(DDRpcTransport.forChannel(secondBackend), OPTIONS);
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
            for (DDMessage message : desktop.sent) {
                if ("REQUEST".equals(message.payload().get("type").getAsString())) {
                    Assertions.assertTrue(List.of(firstTopic, secondTopic).contains(message.topic()));
                    JsonElement envelope = message.payload().get("data");
                    Assertions.assertFalse(envelope.getAsJsonObject().has("topic"));
                    Assertions.assertEquals("dbeaver.rpc.v2", envelope.getAsJsonObject().get("protocol").getAsString());
                }
            }
            firstGreeting.close();
            RpcException missing = Assertions.assertThrows(RpcException.class,
                () -> first.hello("closed"));
            Assertions.assertEquals(RpcMessage.Error.UNKNOWN_SERVICE, missing.remoteError());
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

    private record PrefixCodec(@NotNull String encodedPrefix, @NotNull String decodedPrefix) implements RpcCodec<JsonElement> {
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

    private static final class Channel implements DDMessageChannel {
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final List<Consumer<DDChannelStatus>> observers = new CopyOnWriteArrayList<>();
        private final List<DDMessage> sent = new CopyOnWriteArrayList<>();
        private Consumer<DDMessage> forward = message -> { };
        private boolean closed;

        @Override
        @NotNull
        public DDChannelStatus getStatus() {
            return new DDChannelStatus(closed ? DDChannelState.CLOSED : DDChannelState.CONNECTED, closed ? null : CONNECTION);
        }

        @Override
        @NotNull
        public DDSubscription observeState(@NotNull Consumer<DDChannelStatus> observer) {
            observers.add(observer);
            observer.accept(getStatus());
            return () -> observers.remove(observer);
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull DDMessage message) {
            sent.add(message);
            forward.accept(message);
            return CompletableFuture.completedFuture(null);
        }

        private void receive(@NotNull DDMessage message) {
            for (Subscription subscription : subscriptions) {
                if (subscription.topic.equals(message.topic())) {
                    subscription.listener.onMessage(CONNECTION, message);
                }
            }
        }

        @Override
        @NotNull
        public DDSubscription subscribe(@NotNull String topic, @NotNull DDMessageListener listener) {
            var subscription = new Subscription(topic, listener);
            subscriptions.add(subscription);
            return () -> subscriptions.remove(subscription);
        }

        @Override
        @NotNull
        public DDSubscription addConnectionListener(@NotNull DDConnectionListener listener) {
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

    private record Subscription(@NotNull String topic, @NotNull DDMessageListener listener) {
    }
}
