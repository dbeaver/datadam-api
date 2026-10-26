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
        MessagingRpcMessage<Object> request = MessagingRpcMessage.request("echo(java.lang.String)", payload);

        assertEquals(MessagingRpcMessage.Type.REQUEST, request.type());
        assertEquals("echo(java.lang.String)", request.operation());
        assertSame(payload, request.payload());
        assertNull(request.error());
        assertNull(MessagingRpcMessage.request("echo(java.lang.String)", null).payload());

        MessagingRpcMessage<Object> response = MessagingRpcMessage.response(payload);
        assertEquals(MessagingRpcMessage.Type.RESPONSE, response.type());
        assertSame(payload, response.payload());
        assertNull(response.operation());
        assertNull(response.error());
        assertEquals(new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, null, null, null),
            MessagingRpcMessage.response(null));
    }

    @Test
    void failuresCanCarryACodeWithoutExceptionDetails() {
        for (MessagingRpcMessage.Error error : MessagingRpcMessage.Error.values()) {
            MessagingRpcMessage<Object> failure = MessagingRpcMessage.failure(error);

            assertEquals(MessagingRpcMessage.Type.ERROR, failure.type());
            assertEquals(error, failure.error());
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
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST, "echo(int)", 1, null, details));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, 1, null, details));
    }

    @Test
    void validatesOperationBoundsWithoutRejectingTheBoundary() {
        String operation = "o".repeat(1024);
        MessagingRpcMessage<Object> request = MessagingRpcMessage.request(operation, null);
        assertEquals(operation, request.operation());

        for (String invalid : List.of("", " ", "\n", "o".repeat(1025))) {
            assertThrows(IllegalArgumentException.class, () -> MessagingRpcMessage.request(invalid, null));
        }
        assertThrows(IllegalArgumentException.class, () -> MessagingRpcMessage.request(null, null));
        assertThrows(NullPointerException.class, () -> new MessagingRpcMessage<>(null, null, null, null, null));
    }

    @Test
    void rejectsRoutingFieldsOnRepliesAndInconsistentPayloadOrErrorFields() {
        for (MessagingRpcMessage.Type type : List.of(MessagingRpcMessage.Type.RESPONSE, MessagingRpcMessage.Type.ERROR)) {
            MessagingRpcMessage.Error error = type == MessagingRpcMessage.Type.ERROR ? MessagingRpcMessage.Error.HANDLER_FAILED : null;
            assertThrows(IllegalArgumentException.class, () -> new MessagingRpcMessage<>(type, "echo(int)", null, error, null));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.ERROR, null, null, null, null));
        for (MessagingRpcMessage.Error error : MessagingRpcMessage.Error.values()) {
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.REQUEST, "echo(int)", null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.RESPONSE, null, null, error, null));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRpcMessage<>(MessagingRpcMessage.Type.ERROR, null, "private payload", error, null));
        }
    }
}
