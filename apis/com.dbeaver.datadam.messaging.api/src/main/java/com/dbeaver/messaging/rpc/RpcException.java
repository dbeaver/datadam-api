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

import com.dbeaver.messaging.requestreply.RequestReplyException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Objects;

public class RpcException extends RuntimeException {
    public enum Reason {
        CLOSED, DISCONNECTED, OVERLOADED, SEND_FAILED, TIMEOUT, INTERRUPTED, CODEC_FAILED, INVALID_RESPONSE, REMOTE_ERROR
    }

    private final Reason reason;
    @Nullable
    private final RpcMessage.Error remoteError;
    @Nullable
    private final RpcExceptionDetails remoteException;

    public RpcException(@NotNull Reason reason) {
        this(reason, null);
    }

    public RpcException(@NotNull Reason reason, @Nullable Throwable cause) {
        super("RPC call failed: " + Objects.requireNonNull(reason), cause);
        this.reason = reason;
        this.remoteError = null;
        this.remoteException = null;
    }

    public RpcException(@NotNull RpcMessage.Error remoteError) {
        this(remoteError, null);
    }

    public RpcException(@NotNull RpcMessage.Error remoteError, @Nullable RpcExceptionDetails remoteException) {
        super("Remote RPC call failed: " + Objects.requireNonNull(remoteError)
            + (remoteException == null ? "" : ": " + remoteException.type()
                + (remoteException.message() == null ? "" : ": " + remoteException.message())),
            remoteException == null ? null : new RemoteException(remoteException));
        this.reason = Reason.REMOTE_ERROR;
        this.remoteError = remoteError;
        this.remoteException = remoteException;
    }

    RpcException(@NotNull RequestReplyException cause) {
        this(switch (cause.reason()) {
            case CLOSED -> Reason.CLOSED;
            case DISCONNECTED -> Reason.DISCONNECTED;
            case OVERLOADED -> Reason.OVERLOADED;
            case SEND_FAILED -> Reason.SEND_FAILED;
            case TIMEOUT -> Reason.TIMEOUT;
            case REMOTE_ERROR -> Reason.REMOTE_ERROR;
        }, cause);
    }

    @NotNull
    public Reason reason() {
        return reason;
    }

    @Nullable
    public RpcMessage.Error remoteError() {
        return remoteError;
    }

    @Nullable
    public RpcExceptionDetails remoteException() {
        return remoteException;
    }

    private static final class RemoteException extends Exception {
        private RemoteException(@NotNull RpcExceptionDetails details) {
            super("Remote stack trace" + (details.truncated() ? " (truncated)" : "") + ":\n" + details.stackTrace(),
                null, false, false);
        }
    }
}
