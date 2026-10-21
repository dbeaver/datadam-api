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
import com.dbeaver.messaging.requestreply.RequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

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
        try (var requests = RequestReplyEndpoint.open(textTransport, OPTIONS);
             RpcEndpoint text = RpcEndpoint.open(requests, new TextCodec());
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

        assertSame(failure, assertThrows(IllegalStateException.class, endpoint::close));

        assertTrue(endpoint.isClosed());
        assertTrue(endpoint.isCloseComplete());
        endpoint.close();
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

    private static final class LoopbackTransport<P> implements RequestReplyTransport<RpcMessage<P>> {
        private final Map<UUID, Listener<RpcMessage<P>>> listeners = new ConcurrentHashMap<>();
        private final Runnable onClose;

        private LoopbackTransport(@NotNull Runnable onClose) {
            this.onClose = onClose;
        }

        @NotNull
        @Override
        public MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<RpcMessage<P>> listener) {
            listeners.put(endpointId, listener);
            return () -> {
                listeners.remove(endpointId, listener);
                onClose.run();
            };
        }

        @NotNull
        @Override
        public CompletionStage<Void> send(@NotNull RequestReplyMessage<RpcMessage<P>> message) {
            List.copyOf(listeners.values()).forEach(listener -> listener.onMessage(message));
            return CompletableFuture.completedFuture(null);
        }
    }
}
