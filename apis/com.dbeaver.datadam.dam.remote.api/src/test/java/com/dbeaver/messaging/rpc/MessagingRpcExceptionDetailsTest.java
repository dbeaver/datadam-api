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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MessagingRpcExceptionDetailsTest {
    @Test
    void capturesStandardTextIncludingCausesSuppressedExceptionsAndOriginalFrames() {
        var cause = new IllegalArgumentException("Invalid argument");
        var failure = new IllegalStateException("Operation failed", cause);
        var suppressed = new IOException("Cleanup failed");
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement("Receiver", "handle", "Receiver.java", 42)});
        cause.setStackTrace(new StackTraceElement[] {new StackTraceElement("Repository", "load", "Repository.java", 17)});
        suppressed.setStackTrace(new StackTraceElement[] {new StackTraceElement("Resource", "close", "Resource.java", 9)});
        failure.addSuppressed(suppressed);
        StringWriter expected = new StringWriter();
        failure.printStackTrace(new PrintWriter(expected));

        MessagingRpcExceptionDetails details = MessagingRpcExceptionDetails.capture(failure);

        assertEquals(IllegalStateException.class.getName(), details.type());
        assertEquals("Operation failed", details.message());
        assertEquals(expected.toString(), details.stackTrace());
        assertTrue(details.stackTrace().contains("Caused by: " + cause));
        assertTrue(details.stackTrace().contains("Suppressed: " + IOException.class.getName()));
        assertFalse(details.truncated());
    }

    @Test
    void preservesNullMessagesAndTerminatesCircularExceptionGraphs() {
        var failure = new Exception();
        var cause = new Exception("Cause", failure);
        failure.setStackTrace(new StackTraceElement[0]);
        cause.setStackTrace(new StackTraceElement[0]);
        failure.initCause(cause);
        failure.addSuppressed(cause);

        MessagingRpcExceptionDetails details = MessagingRpcExceptionDetails.capture(failure);

        assertNull(details.message());
        assertTrue(details.stackTrace().contains("CIRCULAR REFERENCE"));
        assertFalse(details.truncated());
    }

    @Test
    void boundsMessagesAndTraceTextWithoutSplittingSurrogatePairs() {
        String message = "m".repeat(MessagingRpcExceptionDetails.MAX_MESSAGE_LENGTH - 1) + "😀tail";
        var failure = new IllegalStateException(message) {
            @Override
            public void printStackTrace(@NotNull PrintWriter writer) {
                writer.print("s".repeat(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH - 1) + "😀tail");
            }
        };

        MessagingRpcExceptionDetails details = MessagingRpcExceptionDetails.capture(failure);

        assertEquals("m".repeat(MessagingRpcExceptionDetails.MAX_MESSAGE_LENGTH - 1), details.message());
        assertEquals("s".repeat(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH - 1), details.stackTrace());
        assertTrue(details.truncated());
    }

    @Test
    void stopsFormattingAtTheLimitInsteadOfBuildingAnUnboundedString() {
        AtomicInteger progress = new AtomicInteger();
        var failure = new IllegalStateException("Failure") {
            @Override
            public void printStackTrace(@NotNull PrintWriter writer) {
                writer.print("x".repeat(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH));
                progress.incrementAndGet();
                writer.write(new char[] {'y'}, 0, 1);
                progress.incrementAndGet();
                throw new AssertionError("Formatting continued past the limit");
            }
        };

        MessagingRpcExceptionDetails details = MessagingRpcExceptionDetails.capture(failure);

        assertEquals(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH, details.stackTrace().length());
        assertEquals(1, progress.get());
        assertTrue(details.truncated());
    }

    @Test
    void preservesTheFailureCodeEvenIfCustomExceptionFormattingFails() {
        var failure = new IllegalStateException() {
            @Override
            @Nullable
            public String getMessage() {
                throw new IllegalStateException("Message unavailable");
            }
        };

        MessagingRpcExceptionDetails details = MessagingRpcExceptionDetails.capture(failure);

        assertEquals(failure.getClass().getName(), details.type());
        assertNull(details.message());
        assertEquals(details.type(), details.stackTrace());
        assertTrue(details.truncated());
    }

    @Test
    void validatesAllBoundsWithoutRejectingTheirExactLimits() {
        var details = new MessagingRpcExceptionDetails("t".repeat(MessagingRpcExceptionDetails.MAX_TYPE_LENGTH),
            "m".repeat(MessagingRpcExceptionDetails.MAX_MESSAGE_LENGTH),
            "s".repeat(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH), false);

        assertFalse(details.truncated());
        assertThrows(NullPointerException.class, () -> new MessagingRpcExceptionDetails(null, null, "", false));
        assertThrows(NullPointerException.class, () -> new MessagingRpcExceptionDetails("type", null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new MessagingRpcExceptionDetails(" ", null, "", false));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcExceptionDetails("t".repeat(MessagingRpcExceptionDetails.MAX_TYPE_LENGTH + 1), null, "", false));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcExceptionDetails("type", "m".repeat(MessagingRpcExceptionDetails.MAX_MESSAGE_LENGTH + 1), "", false));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcExceptionDetails("type", null,
                "s".repeat(MessagingRpcExceptionDetails.MAX_STACK_TRACE_LENGTH + 1), false));
    }
}
