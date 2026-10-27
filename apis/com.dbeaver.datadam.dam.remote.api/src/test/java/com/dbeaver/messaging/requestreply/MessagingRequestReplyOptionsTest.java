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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MessagingRequestReplyOptionsTest {
    @Test
    void acceptsPositiveLimitsAndRepresentableTimeoutBoundaries() {
        for (Duration timeout : List.of(Duration.ofNanos(1), Duration.ofNanos(Long.MAX_VALUE))) {
            for (int limit : List.of(1, Integer.MAX_VALUE)) {
                var options = new MessagingRequestReplyOptions(timeout, limit, limit);

                assertEquals(timeout, options.timeout());
                assertEquals(limit, options.maxPendingRequests());
                assertEquals(limit, options.maxConcurrentHandlers());
            }
        }
        assertTrue(MessagingRequestReplyOptions.DEFAULT.timeout().isPositive());
        assertTrue(MessagingRequestReplyOptions.DEFAULT.maxPendingRequests() > 0);
        assertTrue(MessagingRequestReplyOptions.DEFAULT.maxConcurrentHandlers() > 0);
    }

    @Test
    void rejectsNonPositiveAndOverflowingTimeouts() {
        for (Duration timeout : List.of(
            Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(-1),
            Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MIN_VALUE)
        )) {
            assertThrows(IllegalArgumentException.class, () -> new MessagingRequestReplyOptions(timeout, 1, 1));
        }
        assertThrows(NullPointerException.class, () -> new MessagingRequestReplyOptions(null, 1, 1));
    }

    @Test
    void rejectsNonPositivePendingAndHandlerLimitsIndependently() {
        for (int limit : List.of(0, -1, Integer.MIN_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new MessagingRequestReplyOptions(Duration.ofSeconds(1), limit, 1));
            assertThrows(IllegalArgumentException.class, () -> new MessagingRequestReplyOptions(Duration.ofSeconds(1), 1, limit));
        }
    }
}
