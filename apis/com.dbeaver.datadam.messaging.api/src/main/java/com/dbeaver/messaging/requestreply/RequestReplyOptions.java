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

import java.time.Duration;
import java.util.Objects;

public record RequestReplyOptions(@NotNull Duration timeout, int maxPendingRequests, int maxConcurrentHandlers) {
    public static final RequestReplyOptions DEFAULT = new RequestReplyOptions(Duration.ofSeconds(30), 256, 64);

    public RequestReplyOptions {
        requireTimeout(timeout);
        if (maxPendingRequests < 1 || maxConcurrentHandlers < 1) {
            throw new IllegalArgumentException("Request/reply limits must be positive");
        }
    }

    static long requireTimeout(@NotNull Duration timeout) {
        Objects.requireNonNull(timeout);
        try {
            long nanos = timeout.toNanos();
            if (nanos > 0) {
                return nanos;
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Request/reply timeout is too large", e);
        }
        throw new IllegalArgumentException("Request/reply timeout must be positive");
    }
}
