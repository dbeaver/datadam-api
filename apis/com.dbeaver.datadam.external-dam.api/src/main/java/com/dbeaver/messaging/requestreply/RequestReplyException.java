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
import org.jkiss.code.Nullable;

import java.util.Objects;

public class RequestReplyException extends Exception {
    public enum Reason {
        CLOSED, DISCONNECTED, OVERLOADED, SEND_FAILED, TIMEOUT, REMOTE_ERROR
    }

    private final Reason reason;
    @Nullable
    private final RequestReplyMessage.Error remoteError;

    public RequestReplyException(@NotNull Reason reason) {
        this(reason, null);
    }

    public RequestReplyException(@NotNull Reason reason, @Nullable Throwable cause) {
        super(message(Objects.requireNonNull(reason)), cause);
        this.reason = reason;
        this.remoteError = null;
    }

    public RequestReplyException(@NotNull RequestReplyMessage.Error remoteError) {
        super("Remote request failed: " + Objects.requireNonNull(remoteError));
        this.reason = Reason.REMOTE_ERROR;
        this.remoteError = remoteError;
    }

    @NotNull
    public Reason reason() {
        return reason;
    }

    @Nullable
    public RequestReplyMessage.Error remoteError() {
        return remoteError;
    }

    @NotNull
    private static String message(@NotNull Reason reason) {
        return switch (reason) {
            case CLOSED -> "Request/reply endpoint is closed";
            case DISCONNECTED -> "Request/reply transport was disconnected";
            case OVERLOADED -> "Request/reply capacity is unavailable";
            case SEND_FAILED -> "Request/reply message could not be sent";
            case TIMEOUT -> "Request/reply deadline expired";
            case REMOTE_ERROR -> "Remote request failed";
        };
    }
}
