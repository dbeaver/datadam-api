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

import java.util.Objects;

public record RpcMessage<P>(
    @NotNull Type type,
    @Nullable String operation,
    @Nullable P payload,
    @Nullable Error error,
    @Nullable RpcExceptionDetails exception
) {
    public enum Type {
        REQUEST, RESPONSE, ERROR
    }

    public enum Error {
        UNKNOWN_SERVICE, UNKNOWN_OPERATION, INVALID_REQUEST, HANDLER_FAILED, CODEC_FAILED
    }

    public RpcMessage {
        Objects.requireNonNull(type);
        if (exception != null && type != Type.ERROR) {
            throw new IllegalArgumentException("RPC exception details require an error response");
        }
        if (type == Type.REQUEST) {
            if (operation == null || operation.isBlank() || operation.length() > 1024 || error != null) {
                throw new IllegalArgumentException("Invalid RPC request");
            }
        } else if (operation != null || type == Type.ERROR && (error == null || payload != null)
            || type == Type.RESPONSE && error != null) {
            throw new IllegalArgumentException("Invalid RPC response");
        }
    }

    @NotNull
    public static <P> RpcMessage<P> request(@NotNull String operation, @Nullable P payload) {
        return new RpcMessage<>(Type.REQUEST, operation, payload, null, null);
    }

    @NotNull
    public static <P> RpcMessage<P> response(@Nullable P payload) {
        return new RpcMessage<>(Type.RESPONSE, null, payload, null, null);
    }

    @NotNull
    public static <P> RpcMessage<P> failure(@NotNull Error error) {
        return failure(error, null);
    }

    @NotNull
    public static <P> RpcMessage<P> failure(@NotNull Error error, @Nullable RpcExceptionDetails exception) {
        return new RpcMessage<>(Type.ERROR, null, null, Objects.requireNonNull(error), exception);
    }
}
