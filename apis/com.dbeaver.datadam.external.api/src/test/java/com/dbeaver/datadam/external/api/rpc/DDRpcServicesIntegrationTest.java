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
import com.dbeaver.messaging.rpc.RpcEndpoint;
import com.dbeaver.messaging.rpc.RpcException;
import com.dbeaver.messaging.rpc.RpcMessage;
import com.google.gson.Gson;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Timeout(10)
class DDRpcServicesIntegrationTest {
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("desktop", "run", "physical-connection");
    private static final RequestReplyOptions OPTIONS = new RequestReplyOptions(Duration.ofSeconds(2), 16, 8);

    @Test
    void servesAccountAndStorageBidirectionallyOverOneDesktopChannel() throws Exception {
        Channel desktop = new Channel();
        Channel account = new Channel();
        Channel storage = new Channel();
        desktop.forward = message -> {
            account.receive(message);
            storage.receive(message);
        };
        account.forward = desktop::receive;
        storage.forward = desktop::receive;
        var codec = new DDGsonRpcCodec(new Gson());
        try (var desktopAccount = RpcEndpoint.open(
                 DDRpcTransport.forChannel(desktop, DDRpcServices.topic(DDRpcServices.ACCOUNT)), codec, OPTIONS);
             var desktopStorage = RpcEndpoint.open(
                 DDRpcTransport.forChannel(desktop, DDRpcServices.topic(DDRpcServices.STORAGE)), codec, OPTIONS);
             var accountRpc = RpcEndpoint.open(
                 DDRpcTransport.forChannel(account, DDRpcServices.topic(DDRpcServices.ACCOUNT)), codec, OPTIONS);
             var storageRpc = RpcEndpoint.open(
                 DDRpcTransport.forChannel(storage, DDRpcServices.topic(DDRpcServices.STORAGE)), codec, OPTIONS);
             var accountGreeting = accountRpc.register("greeting", Greeting.class, value -> value + " World from Account");
             var storageGreeting = storageRpc.register("greeting", Greeting.class, value -> value + " World from Storage");
             var accountCounter = desktopAccount.register("counter", Counter.class, value -> value + 1);
             var storageCounter = desktopStorage.register("counter", Counter.class, value -> value + 10)) {
            Assertions.assertEquals("Hello World from Account", desktopAccount.proxy("greeting", Greeting.class).hello("Hello"));
            Assertions.assertEquals("Hello World from Storage", desktopStorage.proxy("greeting", Greeting.class).hello("Hello"));
            Assertions.assertEquals(2, accountRpc.proxy("counter", Counter.class).increment(1));
            Assertions.assertEquals(11, storageRpc.proxy("counter", Counter.class).increment(1));

            accountCounter.close();

            RpcException missing = Assertions.assertThrows(RpcException.class,
                () -> accountRpc.proxy("counter", Counter.class).increment(1));
            Assertions.assertEquals(RpcMessage.Error.UNKNOWN_SERVICE, missing.remoteError());
            Assertions.assertEquals(11, storageRpc.proxy("counter", Counter.class).increment(1));
            Assertions.assertEquals("Still World from Account", desktopAccount.proxy("greeting", Greeting.class).hello("Still"));
        }
        for (Channel channel : List.of(desktop, account, storage)) {
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

    /** Increments a value on the selected Desktop lane. */
    public interface Counter {
        int increment(int value);
    }

    private static final class Channel implements DDMessageChannel {
        private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final List<Consumer<DDChannelStatus>> observers = new CopyOnWriteArrayList<>();
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
