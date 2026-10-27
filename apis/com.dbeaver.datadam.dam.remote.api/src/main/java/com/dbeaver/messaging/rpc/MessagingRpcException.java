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

import com.dbeaver.messaging.requestreply.MessagingRequestReplyException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Objects;

public class MessagingRpcException extends RuntimeException {
    public enum Reason {
        CLOSED, DISCONNECTED, OVERLOADED, SEND_FAILED, TIMEOUT, INTERRUPTED, CODEC_FAILED, INVALID_RESPONSE, REMOTE_ERROR
    }

    private final Reason reason;
    @Nullable
    private final MessagingRpcMessage.Error remoteError;
    @Nullable
    private final MessagingRpcExceptionDetails remoteException;

    public MessagingRpcException(@NotNull Reason reason) {
        this(reason, null);
    }

    public MessagingRpcException(@NotNull Reason reason, @Nullable Throwable cause) {
        super("RPC call failed: " + Objects.requireNonNull(reason), cause);
        this.reason = reason;
        this.remoteError = null;
        this.remoteException = null;
    }

    public MessagingRpcException(@NotNull MessagingRpcMessage.Error remoteError) {
        this(remoteError, null);
    }

    public MessagingRpcException(@NotNull MessagingRpcMessage.Error remoteError, @Nullable MessagingRpcExceptionDetails remoteException) {
        super("Remote RPC call failed: " + Objects.requireNonNull(remoteError)
            + (remoteException == null ? "" : ": " + remoteException.type()
                + (remoteException.message() == null ? "" : ": " + remoteException.message())),
            remoteException == null ? null : new RemoteException(remoteException));
        this.reason = Reason.REMOTE_ERROR;
        this.remoteError = remoteError;
        this.remoteException = remoteException;
    }

    MessagingRpcException(@NotNull MessagingRequestReplyException cause) {
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
    public MessagingRpcMessage.Error remoteError() {
        return remoteError;
    }

    @Nullable
    public MessagingRpcExceptionDetails remoteException() {
        return remoteException;
    }

    private static final class RemoteException extends Exception {
        private RemoteException(@NotNull MessagingRpcExceptionDetails details) {
            super("Remote stack trace" + (details.truncated() ? " (truncated)" : "") + ":\n" + details.stackTrace(),
                null, false, false);
        }
    }
}
