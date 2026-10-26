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
package com.dbeaver.datadam.messaging.api.rpc;

import com.dbeaver.messaging.rpc.MessagingRpcCodec;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

public final class DDMessagingGsonRpcCodec implements MessagingRpcCodec<JsonElement> {
    private final Gson gson;

    public DDMessagingGsonRpcCodec(@NotNull Gson gson) {
        this.gson = Objects.requireNonNull(gson);
    }

    @Override
    @Nullable
    public JsonElement encode(@Nullable Object value, @NotNull Type type) {
        JsonElement encoded = gson.toJsonTree(value, Objects.requireNonNull(type));
        return encoded == null || encoded.isJsonNull() ? null : encoded;
    }

    @Override
    @Nullable
    public Object decode(@Nullable JsonElement payload, @NotNull Type type) {
        return gson.fromJson(payload, Objects.requireNonNull(type));
    }
}
