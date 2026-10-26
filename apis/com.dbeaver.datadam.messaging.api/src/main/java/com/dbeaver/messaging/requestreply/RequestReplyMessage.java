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
import java.util.UUID;

public record RequestReplyMessage<P>(
    @NotNull UUID requestId,
    @NotNull UUID replyTo,
    @NotNull Type type,
    @Nullable P payload,
    @Nullable Error error
) {
    public enum Type {
        REQUEST, RESPONSE, ERROR
    }

    public enum Error {
        NO_HANDLER, OVERLOADED, HANDLER_FAILED
    }

    public RequestReplyMessage {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(replyTo);
        Objects.requireNonNull(type);
        if (type == Type.REQUEST && payload == null || type == Type.ERROR && (error == null || payload != null)
            || type != Type.ERROR && error != null) {
            throw new IllegalArgumentException("Invalid request/reply message");
        }
    }

    @NotNull
    public static <P> RequestReplyMessage<P> request(@NotNull UUID requestId, @NotNull UUID replyTo, @NotNull P payload) {
        return new RequestReplyMessage<>(requestId, replyTo, Type.REQUEST, Objects.requireNonNull(payload), null);
    }

    @NotNull
    public RequestReplyMessage<P> response(@Nullable P result) {
        requireRequest();
        return new RequestReplyMessage<>(requestId, replyTo, Type.RESPONSE, result, null);
    }

    @NotNull
    public RequestReplyMessage<P> failure(@NotNull Error reason) {
        requireRequest();
        return new RequestReplyMessage<>(requestId, replyTo, Type.ERROR, null, Objects.requireNonNull(reason));
    }

    private void requireRequest() {
        if (type != Type.REQUEST) {
            throw new IllegalStateException("Only a request can be answered");
        }
    }
}
