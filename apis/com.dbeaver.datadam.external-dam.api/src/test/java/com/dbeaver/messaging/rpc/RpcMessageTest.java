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
class RpcMessageTest {
    @Test
    void preservesOpaquePayloadsAndAllowsNullApplicationValues() {
        Object payload = new Object();
        RpcMessage<Object> request = RpcMessage.request("echo(java.lang.String)", payload);

        assertEquals(RpcMessage.Type.REQUEST, request.type());
        assertEquals("echo(java.lang.String)", request.operation());
        assertSame(payload, request.payload());
        assertNull(request.error());
        assertNull(RpcMessage.request("echo(java.lang.String)", null).payload());

        RpcMessage<Object> response = RpcMessage.response(payload);
        assertEquals(RpcMessage.Type.RESPONSE, response.type());
        assertSame(payload, response.payload());
        assertNull(response.operation());
        assertNull(response.error());
        assertEquals(new RpcMessage<>(RpcMessage.Type.RESPONSE, null, null, null), RpcMessage.response(null));
    }

    @Test
    void failuresCarryOnlyASafeErrorCode() {
        for (RpcMessage.Error error : RpcMessage.Error.values()) {
            RpcMessage<Object> failure = RpcMessage.failure(error);

            assertEquals(RpcMessage.Type.ERROR, failure.type());
            assertEquals(error, failure.error());
            assertNull(failure.operation());
            assertNull(failure.payload());
        }
        assertThrows(NullPointerException.class, () -> RpcMessage.failure(null));
    }

    @Test
    void validatesOperationBoundsWithoutRejectingTheBoundary() {
        String operation = "o".repeat(1024);
        RpcMessage<Object> request = RpcMessage.request(operation, null);
        assertEquals(operation, request.operation());

        for (String invalid : List.of("", " ", "\n", "o".repeat(1025))) {
            assertThrows(IllegalArgumentException.class, () -> RpcMessage.request(invalid, null));
        }
        assertThrows(IllegalArgumentException.class, () -> RpcMessage.request(null, null));
        assertThrows(NullPointerException.class, () -> new RpcMessage<>(null, null, null, null));
    }

    @Test
    void rejectsRoutingFieldsOnRepliesAndInconsistentPayloadOrErrorFields() {
        for (RpcMessage.Type type : List.of(RpcMessage.Type.RESPONSE, RpcMessage.Type.ERROR)) {
            RpcMessage.Error error = type == RpcMessage.Type.ERROR ? RpcMessage.Error.HANDLER_FAILED : null;
            assertThrows(IllegalArgumentException.class, () -> new RpcMessage<>(type, "echo(int)", null, error));
        }
        assertThrows(IllegalArgumentException.class, () -> new RpcMessage<>(RpcMessage.Type.ERROR, null, null, null));
        for (RpcMessage.Error error : RpcMessage.Error.values()) {
            assertThrows(IllegalArgumentException.class,
                () -> new RpcMessage<>(RpcMessage.Type.REQUEST, "echo(int)", null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new RpcMessage<>(RpcMessage.Type.RESPONSE, null, null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new RpcMessage<>(RpcMessage.Type.ERROR, null, "private payload", error));
        }
    }
}
