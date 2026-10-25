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

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcExceptionTest {
    @Test
    void includesRemoteTextInOrdinaryExceptionLoggingWithoutLoadingTheRemoteClass() {
        var details = new RpcExceptionDetails("unavailable.application.Failure", "Application failed",
            "unavailable.application.Failure: Application failed\n\tat remote.Receiver.call(Receiver.java:42)", true);

        var failure = new RpcException(RpcMessage.Error.HANDLER_FAILED, details);
        StringWriter printed = new StringWriter();
        failure.printStackTrace(new PrintWriter(printed));

        assertEquals(RpcException.Reason.REMOTE_ERROR, failure.reason());
        assertEquals(RpcMessage.Error.HANDLER_FAILED, failure.remoteError());
        assertSame(details, failure.remoteException());
        assertTrue(failure.getMessage().contains(details.type()));
        assertTrue(failure.getMessage().contains(details.message()));
        assertTrue(printed.toString().contains(details.stackTrace()));
        assertTrue(printed.toString().contains("Remote stack trace (truncated)"));
        assertNotEquals(details.type(), failure.getCause().getClass().getName());
        assertEquals(0, failure.getCause().getStackTrace().length);
        assertTrue(failure.getStackTrace().length > 0);
    }

    @Test
    void preservesCodeOnlyErrorsAndLocalCausesWithoutInventingRemoteDiagnostics() {
        var remote = new RpcException(RpcMessage.Error.UNKNOWN_SERVICE);
        assertEquals("Remote RPC call failed: UNKNOWN_SERVICE", remote.getMessage());
        assertNull(remote.getCause());
        assertNull(remote.remoteException());

        var cause = new IllegalStateException("Local failure");
        var local = new RpcException(RpcException.Reason.SEND_FAILED, cause);
        assertSame(cause, local.getCause());
        assertNull(local.remoteException());
        assertNull(local.remoteError());
        assertFalse(local.getMessage().contains(cause.getMessage()));
    }
}
