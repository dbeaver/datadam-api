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
package com.dbeaver.messaging.rpc;

import com.dbeaver.messaging.requestreply.MessageSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyException;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class RpcEndpointFacadeTest extends RpcTestSupport {
    @Test
    void usesTheSameFacadeForTextAndBinaryPayloadRepresentations() throws Exception {
        var textTransport = new LoopbackTransport<String>(() -> { });
        var binaryTransport = new LoopbackTransport<byte[]>(() -> { });
        try (RpcEndpoint text = RpcEndpoint.open(textTransport, new TextCodec());
             RpcEndpoint binary = RpcEndpoint.open(binaryTransport, new BinaryCodec(), OPTIONS)) {
            for (RpcEndpoint endpoint : List.of(text, binary)) {
                assertFalse(endpoint.isClosed());
                assertFalse(endpoint.isCloseComplete());
                try (var registration = endpoint.register("text", TextService.class, value -> value)) {
                    TextService proxy = endpoint.proxy("text", TextService.class);
                    assertEquals("value:é", proxy.apply("value:é"));
                    assertNull(proxy.apply(null));
                    endpoint.close();
                    assertTrue(endpoint.isClosed());
                    assertTrue(endpoint.isCloseComplete());
                    failure(RpcException.Reason.CLOSED, () -> proxy.apply("closed"));
                }
            }
        }
    }

    @Test
    void publishesClosedStateBeforeCleanupCompletesAndAllowsAFreshEndpoint() throws Exception {
        var cleanupStarted = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var transport = new LoopbackTransport<String>(() -> {
            cleanupStarted.countDown();
            try {
                await(releaseCleanup);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        try (RpcEndpoint endpoint = RpcEndpoint.open(transport, new TextCodec(), OPTIONS)) {
            endpoint.proxy("text", TextService.class);
            assertFalse(endpoint.isClosed());
            Future<?> closing = async(() -> {
                endpoint.close();
                return null;
            });
            try {
                await(cleanupStarted);
                assertTrue(endpoint.isClosed());
                assertFalse(endpoint.isCloseComplete());
                assertFalse(closing.isDone());
                failure(RpcException.Reason.CLOSED, () -> endpoint.proxy("text", TextService.class));
                endpoint.close();
            } finally {
                releaseCleanup.countDown();
            }
            assertNull(result(closing));
            assertTrue(endpoint.isClosed());
            assertTrue(endpoint.isCloseComplete());
        }
        try (RpcEndpoint reopened = RpcEndpoint.open(transport, new TextCodec(), OPTIONS);
             var registration = reopened.register("text", TextService.class, value -> "fresh:" + value)) {
            assertFalse(reopened.isClosed());
            assertEquals("fresh:value", reopened.proxy("text", TextService.class).apply("value"));
        }
    }

    @Test
    void publishesCleanupCompletionEvenWhenTheTransportCleanupFails() throws Exception {
        IllegalStateException failure = new IllegalStateException("Cleanup failed");
        var transport = new LoopbackTransport<String>(() -> {
            throw failure;
        });
        RpcEndpoint endpoint = RpcEndpoint.open(transport, new TextCodec(), OPTIONS);
        endpoint.proxy("text", TextService.class);

        assertSame(failure, assertThrows(IllegalStateException.class, endpoint::close));

        assertTrue(endpoint.isClosed());
        assertTrue(endpoint.isCloseComplete());
        endpoint.close();
    }

    @Test
    void defaultsAndIndependentBindingCodecsNeverReplaceEachOther() {
        var transport = new MemoryTransport();
        transport.sender = message -> {
            transport.deliver(message);
            return CompletableFuture.completedFuture(null);
        };
        var fallback = new TestCodec();
        var service = new TestCodec();
        var first = new TestCodec();
        final var second = new TestCodec();
        service.decoder = (value, type) -> "service:" + value;
        service.encoder = (value, type) -> "result:" + value;
        first.encoder = (value, type) -> "first:" + value;
        first.decoder = (value, type) -> "one:" + value;
        second.encoder = (value, type) -> "second:" + value;
        second.decoder = (value, type) -> "two:" + value;
        try (var endpoint = RpcEndpoint.open(transport, fallback, OPTIONS);
             var defaultService = endpoint.register("default", TextService.class, value -> value);
             var customService = endpoint.register("custom", TextService.class, value -> value, service)) {
            TextService defaultProxy = endpoint.proxy("default", TextService.class);
            TextService firstProxy = endpoint.proxy("custom", TextService.class, first);
            TextService secondProxy = endpoint.proxy("custom", TextService.class, second);

            assertEquals("before", defaultProxy.apply("before"));
            assertEquals("one:result:service:first:value", firstProxy.apply("value"));
            assertEquals("two:result:service:second:value", secondProxy.apply("value"));
            assertEquals("result:service:fallback", endpoint.proxy("custom", TextService.class).apply("fallback"));
            assertEquals("after", defaultProxy.apply("after"));
            assertEquals(1, first.encoded.size());
            assertEquals(1, first.decoded.size());
            assertEquals(1, second.encoded.size());
            assertEquals(1, second.decoded.size());
            assertEquals(3, service.encoded.size());
            assertEquals(3, service.decoded.size());
            assertEquals(5, fallback.encoded.size());
            assertEquals(5, fallback.decoded.size());
            assertEquals(2, transport.listeners.size());
        }
    }

    @Test
    void shorterProxyTimeoutDoesNotChangeOtherProxiesSharingTheTopic() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, codec, OPTIONS)) {
            TextService first = endpoint.proxy("text", TextService.class);
            TextService expiring = endpoint.proxy("text", TextService.class, Duration.ofMillis(100));
            TextService second = endpoint.proxy("text", TextService.class);
            Future<String> firstCall = async(() -> first.apply("first"));
            var firstRequest = transport.next();
            Future<String> secondCall = async(() -> second.apply("second"));
            var secondRequest = transport.next();

            failure(RpcException.Reason.TIMEOUT, () -> expiring.apply("expire"));
            var expiredRequest = transport.next();

            assertFalse(firstCall.isDone());
            assertFalse(secondCall.isDone());
            transport.deliver(expiredRequest.response(RpcMessage.response("late")));
            assertFalse(firstCall.isDone());
            assertFalse(secondCall.isDone());
            transport.deliver(firstRequest.response(RpcMessage.response("first reply")));
            transport.deliver(secondRequest.response(RpcMessage.response("second reply")));
            assertEquals("first reply", result(firstCall));
            assertEquals("second reply", result(secondCall));
            assertEquals(3, codec.encoded.size());
            assertEquals(2, codec.decoded.size());
            assertTrue(transport.sent.isEmpty());
            assertEquals(1, transport.listeners.size());
        }
        assertEquals(1, transport.subscriptionCloses.get());
    }

    @Test
    void longerProxyTimeoutAndExplicitCodecDoNotChangeTheEndpointDefaults() throws Exception {
        var transport = new MemoryTransport();
        var fallback = new TestCodec();
        var custom = new TestCodec();
        custom.encoder = (value, type) -> "custom:" + value;
        custom.decoder = (value, type) -> "decoded:" + value;
        var options = new RequestReplyOptions(Duration.ofMillis(100), 16, 16);
        try (var endpoint = RpcEndpoint.open(transport, fallback, options)) {
            TextService first = endpoint.proxy("text", TextService.class);
            TextService extended = endpoint.proxy("text", TextService.class, custom, WAIT);
            TextService second = endpoint.proxy("text", TextService.class, fallback);
            Future<String> extendedCall = async(() -> extended.apply("extended"));
            var extendedRequest = transport.next();
            assertEquals("custom:extended", extendedRequest.payload().payload());

            failure(RpcException.Reason.TIMEOUT, () -> first.apply("first"));
            var firstRequest = transport.next();
            failure(RpcException.Reason.TIMEOUT, () -> second.apply("second"));
            var secondRequest = transport.next();

            assertFalse(extendedCall.isDone());
            transport.deliver(firstRequest.response(RpcMessage.response("first late reply")));
            transport.deliver(secondRequest.response(RpcMessage.response("second late reply")));
            assertFalse(extendedCall.isDone());
            transport.deliver(extendedRequest.response(RpcMessage.response("extended reply")));
            assertEquals("decoded:extended reply", result(extendedCall));
            assertEquals(1, custom.encoded.size());
            assertEquals(1, custom.decoded.size());
            assertEquals(2, fallback.encoded.size());
            assertTrue(fallback.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
            assertEquals(1, transport.listeners.size());
        }
        assertEquals(1, transport.subscriptionCloses.get());
    }

    @Test
    void validatesProxyTimeoutsBeforeOpeningAnyTopic() {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, codec, OPTIONS)) {
            for (Duration timeout : List.of(
                Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-1),
                Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MIN_VALUE)
            )) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("text", TextService.class, timeout));
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("text", TextService.class, codec, timeout));
            }
            assertThrows(NullPointerException.class, () -> endpoint.proxy("text", TextService.class, (Duration) null));
            assertThrows(NullPointerException.class, () -> endpoint.proxy("text", TextService.class, codec, null));
            assertThrows(NullPointerException.class, () -> endpoint.proxy("text", TextService.class, null, WAIT));
            assertTrue(transport.listeners.isEmpty());
            for (Duration timeout : List.of(Duration.ofNanos(1), Duration.ofNanos(Long.MAX_VALUE))) {
                endpoint.proxy("text", TextService.class, timeout);
                endpoint.proxy("text", TextService.class, codec, timeout);
            }
            assertEquals(1, transport.listeners.size());
        }
    }

    @Test
    void closesEveryTopicWhenOneSubscriptionCleanupFails() {
        var closes = new AtomicInteger();
        var failure = new IllegalStateException("Cleanup failed");
        var transport = new LoopbackTransport<String>(() -> {
            if (closes.incrementAndGet() == 1) {
                throw failure;
            }
        });
        var endpoint = RpcEndpoint.open(transport, new TextCodec());
        endpoint.proxy("one", TextService.class);
        endpoint.proxy("two", TextService.class);
        endpoint.proxy("three", TextService.class);
        assertSame(failure, assertThrows(IllegalStateException.class, endpoint::close));
        assertEquals(3, closes.get());
        assertTrue(transport.listeners.isEmpty());
        assertTrue(endpoint.isCloseComplete());
    }

    @Test
    void missingDefaultFailsBeforeOpeningAnyTopic() {
        var openings = new AtomicInteger();
        try (var endpoint = RpcEndpoint.<Object>open(topic -> {
            openings.incrementAndGet();
            return new MemoryTransport().forTopic(topic);
        })) {
            IllegalStateException proxyFailure = assertThrows(IllegalStateException.class,
                () -> endpoint.proxy("text", TextService.class));
            assertTrue(proxyFailure.getMessage().contains("default codec"));
            assertThrows(IllegalStateException.class, () -> endpoint.proxy("text", TextService.class, WAIT));
            assertThrows(IllegalStateException.class, () -> endpoint.register("text", TextService.class, value -> value));
            assertEquals(0, openings.get());
        }
    }

    @Test
    void incompatibleBindingPayloadsFailThroughTheCodecErrorBoundary() {
        var transport = new LoopbackTransport<String>(() -> { });
        try (var endpoint = RpcEndpoint.open(transport, new TextCodec());
             var registration = endpoint.register("text", TextService.class, value -> value)) {
            remoteFailure(RpcMessage.Error.CODEC_FAILED,
                () -> endpoint.proxy("text", TextService.class, new BinaryCodec()).apply("incompatible"));
            assertEquals("compatible", endpoint.proxy("text", TextService.class).apply("compatible"));
        }
    }

    @Test
    void concurrentBindingsShareOneOpeningWithoutBlockingOtherTopics() throws Exception {
        var transport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var openings = new AtomicInteger();
        try (var endpoint = RpcEndpoint.<Object>open(topic -> {
            openings.incrementAndGet();
            if (topic.equals("blocked")) {
                entered.countDown();
                try {
                    await(release);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e);
                }
            }
            return transport.forTopic(topic);
        }, OPTIONS)) {
            Future<TextService> first = async(() -> endpoint.proxy("blocked", TextService.class, new TestCodec()));
            try {
                await(entered);
                final Future<TextService> second = async(() -> endpoint.proxy("blocked", TextService.class, new TestCodec()));
                endpoint.proxy("independent", TextService.class, new TestCodec());
                assertEquals(2, openings.get());
                release.countDown();
                assertFalse(result(first).equals(result(second)));
                assertEquals(2, openings.get());
                assertEquals(2, transport.listeners.size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closeCompletionWaitsForLateOpeningAndItsSubscriptionRollback() throws Exception {
        var opening = new CountDownLatch(1);
        var releaseOpening = new CountDownLatch(1);
        var cleanup = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var closes = new AtomicInteger();
        var transport = new LoopbackTransport<String>(() -> {
            closes.incrementAndGet();
            cleanup.countDown();
            try {
                await(releaseCleanup);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        try (var endpoint = RpcEndpoint.<String>open(topic -> {
            opening.countDown();
            try {
                await(releaseOpening);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e);
            }
            return transport.forTopic(topic);
        }, OPTIONS)) {
            Future<TextService> pending = async(() -> endpoint.proxy("text", TextService.class, new TextCodec()));
            try {
                await(opening);
                endpoint.close();
                assertTrue(endpoint.isClosed());
                assertFalse(endpoint.isCloseComplete());
                releaseOpening.countDown();
                await(cleanup);
                endpoint.close();
                assertFalse(endpoint.isCloseComplete());
                releaseCleanup.countDown();
                failure(RpcException.Reason.CLOSED, pending);
                assertTrue(endpoint.isCloseComplete());
                assertEquals(1, closes.get());
                assertTrue(transport.listeners.isEmpty());
            } finally {
                releaseOpening.countDown();
                releaseCleanup.countDown();
            }
        }
    }

    @Test
    void boundsCachedTopicsWithoutReservingDefaultServiceNames() {
        var transport = new MemoryTransport();
        try (var endpoint = RpcEndpoint.open(transport, new TestCodec())) {
            for (int index = 0; index < 64; index++) {
                try (var registration = endpoint.register("topic." + index, TextService.class, value -> value)) {
                    endpoint.proxy("topic." + index, TextService.class);
                }
            }
            assertEquals(64, transport.listeners.size());
            failure(RpcException.Reason.OVERLOADED, () -> endpoint.proxy("account", TextService.class));
            failure(RpcException.Reason.OVERLOADED, () -> endpoint.register("storage", TextService.class, value -> value));
            endpoint.proxy("topic.0", TextService.class);
            assertEquals(64, transport.listeners.size());
        }
        assertEquals(64, transport.subscriptionCloses.get());
    }

    @Test
    void topicCapacityIncludesOpeningsUntilTheirLateRollbackCompletes() throws Exception {
        var transport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var openings = new AtomicInteger();
        try (var endpoint = RpcEndpoint.<Object>open(topic -> {
            openings.incrementAndGet();
            if (topic.equals("reserved")) {
                entered.countDown();
                try {
                    await(release);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RequestReplyException(RequestReplyException.Reason.SEND_FAILED, e);
                }
            }
            return transport.forTopic(topic);
        }, new TestCodec(), OPTIONS)) {
            for (int index = 0; index < 63; index++) {
                endpoint.proxy("topic." + index, TextService.class);
            }
            Future<TextService> pending = async(() -> endpoint.proxy("reserved", TextService.class));
            try {
                await(entered);
                failure(RpcException.Reason.OVERLOADED, () -> endpoint.proxy("excess", TextService.class));
                assertEquals(64, openings.get());
                endpoint.close();
                assertFalse(endpoint.isCloseComplete());
                release.countDown();
                failure(RpcException.Reason.CLOSED, pending);
                assertTrue(endpoint.isCloseComplete());
                assertEquals(64, transport.subscriptionCloses.get());
            } finally {
                release.countDown();
            }
        }
    }

    /** Exchanges nullable text values. */
    public interface TextService {
        @Nullable
        String apply(@Nullable String value);
    }

    private static final class TextCodec implements RpcCodec<String> {
        @Nullable
        @Override
        public String encode(@Nullable Object value, @NotNull Type type) {
            return String.class.cast(value);
        }

        @Nullable
        @Override
        public Object decode(@Nullable String payload, @NotNull Type type) {
            return payload;
        }
    }

    private static final class BinaryCodec implements RpcCodec<byte[]> {
        @Nullable
        @Override
        public byte[] encode(@Nullable Object value, @NotNull Type type) {
            return value == null ? null : String.class.cast(value).getBytes(StandardCharsets.UTF_8);
        }

        @Nullable
        @Override
        public Object decode(@Nullable byte[] payload, @NotNull Type type) {
            return payload == null ? null : new String(payload, StandardCharsets.UTF_8);
        }
    }

    private static final class LoopbackTransport<P> implements RpcTransport<P> {
        private final Map<UUID, RequestReplyTransport.Listener<RpcMessage<P>>> listeners = new ConcurrentHashMap<>();
        private final Map<UUID, String> topics = new ConcurrentHashMap<>();
        private final Runnable onClose;

        private LoopbackTransport(@NotNull Runnable onClose) {
            this.onClose = onClose;
        }

        @NotNull
        @Override
        public RequestReplyTransport<RpcMessage<P>> forTopic(@NotNull String topic) {
            return new RequestReplyTransport<>() {
                @NotNull
                @Override
                public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<RpcMessage<P>> listener) {
                    listeners.put(endpointId, listener);
                    topics.put(endpointId, topic);
                    return () -> {
                        listeners.remove(endpointId, listener);
                        topics.remove(endpointId);
                        onClose.run();
                    };
                }

                @NotNull
                @Override
                public CompletionStage<Void> send(@NotNull RequestReplyMessage<RpcMessage<P>> message) {
                    List.copyOf(listeners.entrySet()).stream().filter(entry -> topic.equals(topics.get(entry.getKey())))
                        .forEach(entry -> entry.getValue().onMessage(message));
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
