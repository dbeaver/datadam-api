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
package com.dbeaver.messaging.requestreply;

import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessagingRequestReplyDeadlineTest {
    @Test
    void rejectsAnExpiredResponseBeforeTheCallerStartsWaiting() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Transport transport = new Transport();
        transport.sending = request -> {
            nanos.set(Duration.ofSeconds(2).toNanos());
            transport.listener.onMessage(request.response("late"));
        };

        try (var endpoint = new MessagingRequestReplyEndpoint<>(transport, MessagingRequestReplyOptions.DEFAULT, nanos::get)) {
            MessagingRequestReplyException failure = assertThrows(MessagingRequestReplyException.class,
                () -> endpoint.request("request", Duration.ofSeconds(1)));
            assertEquals(MessagingRequestReplyException.Reason.TIMEOUT, failure.reason());
        }
    }

    @Test
    void preservesAResponseAcceptedBeforeTheDeadlineWhenTheCallerResumesLater() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Transport transport = new Transport();
        transport.sending = request -> {
            transport.listener.onMessage(request.response("on time"));
            nanos.set(Duration.ofSeconds(2).toNanos());
        };

        try (var endpoint = new MessagingRequestReplyEndpoint<>(transport, MessagingRequestReplyOptions.DEFAULT, nanos::get)) {
            assertEquals("on time", endpoint.request("request", Duration.ofSeconds(1)));
        }
    }

    @Test
    void doesNotSendWhenTheDeadlineExpiresBeforeSending() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Transport transport = new Transport();
        transport.sending = request -> {
            throw new AssertionError("Expired request was sent");
        };

        try (var endpoint = new MessagingRequestReplyEndpoint<>(transport, MessagingRequestReplyOptions.DEFAULT,
            () -> nanos.getAndAdd(Duration.ofSeconds(2).toNanos()))) {
            MessagingRequestReplyException failure = assertThrows(MessagingRequestReplyException.class,
                () -> endpoint.request("request", Duration.ofSeconds(1)));
            assertEquals(MessagingRequestReplyException.Reason.TIMEOUT, failure.reason());
        }
    }

    @Test
    void doesNotSendAPendingRequestAlreadyFailedByDisconnect() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Transport transport = new Transport();
        transport.sending = request -> {
            throw new AssertionError("Disconnected request was sent");
        };

        try (var endpoint = new MessagingRequestReplyEndpoint<>(transport, MessagingRequestReplyOptions.DEFAULT, () -> {
            if (reads.incrementAndGet() == 2) {
                transport.listener.onDisconnect();
            }
            return 0;
        })) {
            MessagingRequestReplyException failure = assertThrows(MessagingRequestReplyException.class, () -> endpoint.request("request"));
            assertEquals(MessagingRequestReplyException.Reason.DISCONNECTED, failure.reason());
        }
    }

    @Test
    void doesNotSendAPendingRequestAlreadyFailedByClosure() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<MessagingRequestReplyEndpoint<String>> current = new AtomicReference<>();
        Transport transport = new Transport();
        transport.sending = request -> {
            throw new AssertionError("Closed request was sent");
        };

        try (var endpoint = new MessagingRequestReplyEndpoint<>(transport, MessagingRequestReplyOptions.DEFAULT, () -> {
            if (reads.incrementAndGet() == 2) {
                current.get().close();
            }
            return 0;
        })) {
            current.set(endpoint);
            MessagingRequestReplyException failure = assertThrows(MessagingRequestReplyException.class, () -> endpoint.request("request"));
            assertEquals(MessagingRequestReplyException.Reason.CLOSED, failure.reason());
        }
    }

    private static final class Transport implements MessagingRequestReplyTransport<String> {
        private Listener<String> listener;
        private Consumer<MessagingRequestReplyMessage<String>> sending;

        @Override
        @NotNull
        public MessagingSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<String> receiver) {
            listener = receiver;
            return () -> { };
        }

        @Override
        @NotNull
        public CompletionStage<Void> send(@NotNull MessagingRequestReplyMessage<String> message) {
            sending.accept(message);
            return CompletableFuture.completedFuture(null);
        }
    }
}
