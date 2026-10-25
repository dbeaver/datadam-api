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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Objects;

public record RpcExceptionDetails(
    @NotNull String type,
    @Nullable String message,
    @NotNull String stackTrace,
    boolean truncated
) {
    public static final int MAX_TYPE_LENGTH = 256;
    public static final int MAX_MESSAGE_LENGTH = 1024;
    public static final int MAX_STACK_TRACE_LENGTH = 8192;

    public RpcExceptionDetails {
        Objects.requireNonNull(type);
        Objects.requireNonNull(stackTrace);
        if (type.isBlank() || type.length() > MAX_TYPE_LENGTH || message != null && message.length() > MAX_MESSAGE_LENGTH
            || stackTrace.length() > MAX_STACK_TRACE_LENGTH) {
            throw new IllegalArgumentException("Invalid RPC exception details");
        }
    }

    @NotNull
    static RpcExceptionDetails capture(@NotNull Throwable failure) {
        String originalType = Objects.requireNonNull(failure).getClass().getName();
        String originalMessage;
        boolean incomplete = false;
        try {
            originalMessage = failure.getMessage();
        } catch (RuntimeException e) {
            originalMessage = null;
            incomplete = true;
        }
        String type = prefix(originalType, MAX_TYPE_LENGTH);
        String message = originalMessage == null ? null : prefix(originalMessage, MAX_MESSAGE_LENGTH);
        TraceWriter trace = new TraceWriter();
        try {
            failure.printStackTrace(new PrintWriter(trace));
        } catch (TraceLimit e) {
            incomplete = true;
        } catch (RuntimeException e) {
            incomplete = true;
        }
        String stackTrace = trace.text.toString();
        if (incomplete && !stackTrace.isEmpty() && Character.isHighSurrogate(stackTrace.charAt(stackTrace.length() - 1))) {
            stackTrace = stackTrace.substring(0, stackTrace.length() - 1);
        }
        if (stackTrace.isEmpty()) {
            stackTrace = type + (message == null ? "" : ": " + message);
        }
        return new RpcExceptionDetails(type, message, stackTrace,
            incomplete || !type.equals(originalType) || !Objects.equals(message, originalMessage));
    }

    @NotNull
    private static String prefix(@NotNull String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) && Character.isLowSurrogate(value.charAt(limit))
            ? limit - 1 : limit;
        return value.substring(0, end);
    }

    private static final class TraceWriter extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(@NotNull char[] value, int offset, int length) {
            int count = Math.min(length, MAX_STACK_TRACE_LENGTH - text.length());
            text.append(value, offset, count);
            requireComplete(length, count);
        }

        @Override
        public void write(@NotNull String value, int offset, int length) {
            int count = Math.min(length, MAX_STACK_TRACE_LENGTH - text.length());
            text.append(value, offset, offset + count);
            requireComplete(length, count);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private static void requireComplete(int length, int count) {
            if (length != count) {
                throw new TraceLimit();
            }
        }
    }

    private static final class TraceLimit extends RuntimeException {
        private TraceLimit() {
            super(null, null, false, false);
        }
    }
}
