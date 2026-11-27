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
package com.dbeaver.datadam.dam.remote.api.rpc;

import com.dbeaver.messaging.rpc.MessagingRpcCodec;
import com.google.gson.Gson;
import com.google.gson.ToNumberPolicy;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

public final class DDRemoteDamGsonRpcCodec implements MessagingRpcCodec<Object> {
    private final Gson gson;

    public DDRemoteDamGsonRpcCodec(@NotNull Gson gson) {
        this.gson = Objects.requireNonNull(gson).newBuilder().serializeNulls()
            .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();
    }

    @Override
    @Nullable
    public Object encode(@Nullable Object value, @NotNull Type type) {
        return gson.fromJson(gson.toJsonTree(value, Objects.requireNonNull(type)), Object.class);
    }

    @Override
    @Nullable
    public Object decode(@Nullable Object payload, @NotNull Type type) {
        return gson.fromJson(gson.toJsonTree(payload), Objects.requireNonNull(type));
    }
}
