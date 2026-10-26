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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record MessagingRpcMessage<P>(
    @NotNull Type type,
    @Nullable String operation,
    @NotNull List<P> arguments,
    @Nullable P payload,
    @Nullable Error error,
    @Nullable MessagingRpcExceptionDetails exception
) {
    public enum Type {
        REQUEST, RESPONSE, ERROR
    }

    public enum Error {
        UNKNOWN_SERVICE, UNKNOWN_OPERATION, INVALID_REQUEST, HANDLER_FAILED, CODEC_FAILED
    }

    public MessagingRpcMessage {
        Objects.requireNonNull(type);
        arguments = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(arguments)));
        if (exception != null && type != Type.ERROR) {
            throw new IllegalArgumentException("RPC exception details require an error response");
        }
        if (type == Type.REQUEST) {
            if (operation == null || operation.isBlank() || operation.length() > 1024 || payload != null || error != null) {
                throw new IllegalArgumentException("Invalid RPC request");
            }
        } else if (operation != null || !arguments.isEmpty() || type == Type.ERROR && (error == null || payload != null)
            || type == Type.RESPONSE && error != null) {
            throw new IllegalArgumentException("Invalid RPC response");
        }
    }

    @NotNull
    public static <P> MessagingRpcMessage<P> request(@NotNull String operation, @NotNull List<P> arguments) {
        return new MessagingRpcMessage<>(Type.REQUEST, operation, arguments, null, null, null);
    }

    @NotNull
    public static <P> MessagingRpcMessage<P> response(@Nullable P payload) {
        return new MessagingRpcMessage<>(Type.RESPONSE, null, List.of(), payload, null, null);
    }

    @NotNull
    public static <P> MessagingRpcMessage<P> failure(@NotNull Error error) {
        return failure(error, null);
    }

    @NotNull
    public static <P> MessagingRpcMessage<P> failure(@NotNull Error error, @Nullable MessagingRpcExceptionDetails exception) {
        return new MessagingRpcMessage<>(Type.ERROR, null, List.of(), null, Objects.requireNonNull(error), exception);
    }
}
