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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class MessagingRpcMessageTest {
    @Test
    void preservesOpaquePayloadsAndAllowsNullApplicationValues() {
        Object payload = new Object();
        MessagingRpcMessage<Object> request = MessagingRpcMessage.request("echo(java.lang.String)", List.of(payload));

        assertEquals(MessagingRpcMessage.Type.REQUEST, request.type());
        assertEquals("echo(java.lang.String)", request.operation());
        assertEquals(List.of(payload), request.arguments());
        assertSame(payload, request.arguments().get(0));
        assertNull(request.payload());
        assertNull(request.error());
        assertNull(request.exception());
        assertEquals(Arrays.asList((Object) null),
            MessagingRpcMessage.request("echo(java.lang.String)", Arrays.asList((Object) null)).arguments());

        MessagingRpcMessage<Object> response = MessagingRpcMessage.response(payload);
        assertEquals(MessagingRpcMessage.Type.RESPONSE, response.type());
        assertSame(payload, response.payload());
        assertEquals(List.of(), response.arguments());
        assertNull(response.operation());
        assertNull(response.error());
        assertEquals(new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, List.of(), null, null, null),
            MessagingRpcMessage.response(null));
    }

    @Test
    void snapshotsArgumentsWithoutCopyingApplicationValuesAndAllowsNullElements() {
        for (boolean factory : List.of(false, true)) {
            var value = new ArrayList<>(List.of("original"));
            var arguments = new ArrayList<Object>(Arrays.asList(value, null));
            MessagingRpcMessage<Object> request = factory
                ? MessagingRpcMessage.request("echo(java.util.List,java.lang.String)", arguments)
                : new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST,
                    "echo(java.util.List,java.lang.String)", arguments, null, null, null);

            arguments.set(0, "replacement");
            arguments.clear();

            assertEquals(Arrays.asList(value, null), request.arguments());
            assertSame(value, request.arguments().get(0));
            value.add("updated");
            assertEquals(List.of("original", "updated"), request.arguments().get(0));
            assertThrows(UnsupportedOperationException.class, () -> request.arguments().add("extra"));
            assertThrows(UnsupportedOperationException.class, () -> request.arguments().set(0, "replacement"));
        }
    }

    @Test
    void requiresANonNullArgumentListForEveryMessageType() {
        assertThrows(NullPointerException.class, () -> MessagingRpcMessage.request("echo()", null));
        for (MessagingRpcMessage.Type type : MessagingRpcMessage.Type.values()) {
            String operation = type == MessagingRpcMessage.Type.REQUEST ? "echo()" : null;
            MessagingRpcMessage.Error error = type == MessagingRpcMessage.Type.ERROR ? MessagingRpcMessage.Error.HANDLER_FAILED : null;
            assertThrows(NullPointerException.class, () -> new MessagingRpcMessage<>(type, operation, null, null, error, null));
        }
    }

    @Test
    void failuresCanCarryACodeWithoutExceptionDetails() {
        for (MessagingRpcMessage.Error error : MessagingRpcMessage.Error.values()) {
            MessagingRpcMessage<Object> failure = MessagingRpcMessage.failure(error);

            assertEquals(MessagingRpcMessage.Type.ERROR, failure.type());
            assertEquals(error, failure.error());
            assertEquals(List.of(), failure.arguments());
            assertNull(failure.operation());
            assertNull(failure.payload());
            assertNull(failure.exception());
        }
        assertThrows(NullPointerException.class, () -> MessagingRpcMessage.failure(null));
    }

    @Test
    void exceptionDetailsBelongOnlyToErrorResponsesAndNeverToApplicationPayloads() {
        var details = new MessagingRpcExceptionDetails("test.ApplicationException", "failure", "remote stack trace", false);
        MessagingRpcMessage<Object> failure = MessagingRpcMessage.failure(MessagingRpcMessage.Error.HANDLER_FAILED, details);

        assertSame(details, failure.exception());
        assertNull(failure.payload());
        assertEquals(MessagingRpcMessage.failure(MessagingRpcMessage.Error.HANDLER_FAILED),
            MessagingRpcMessage.failure(MessagingRpcMessage.Error.HANDLER_FAILED, null));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST, "echo(int)", List.of(1), null, null, details));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, List.of(), 1, null, details));
    }

    @Test
    void validatesOperationBoundsWithoutRejectingTheBoundary() {
        String operation = "o".repeat(1024);
        MessagingRpcMessage<Object> request = MessagingRpcMessage.request(operation, List.of());
        assertEquals(operation, request.operation());

        for (String invalid : List.of("", " ", "\n", "o".repeat(1025))) {
            assertThrows(IllegalArgumentException.class, () -> MessagingRpcMessage.request(invalid, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> MessagingRpcMessage.request(null, List.of()));
        assertThrows(NullPointerException.class, () -> new MessagingRpcMessage<>(null, null, List.of(), null, null, null));
    }

    @Test
    void rejectsRoutingFieldsOnRepliesAndInconsistentPayloadOrErrorFields() {
        for (MessagingRpcMessage.Type type : List.of(MessagingRpcMessage.Type.RESPONSE, MessagingRpcMessage.Type.ERROR)) {
            MessagingRpcMessage.Error error = type == MessagingRpcMessage.Type.ERROR ? MessagingRpcMessage.Error.HANDLER_FAILED : null;
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(type, "echo(int)", List.of(), null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(type, null, List.of("argument"), null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(type, null, Arrays.asList((Object) null), null, error, null));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.ERROR, null, List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST, "echo(int)", List.of(1), "private payload", null, null));
        for (MessagingRpcMessage.Error error : MessagingRpcMessage.Error.values()) {
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST, "echo(int)", List.of(1), null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, List.of(), null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.ERROR, null, List.of(), "private payload", error, null));
        }
    }
}
