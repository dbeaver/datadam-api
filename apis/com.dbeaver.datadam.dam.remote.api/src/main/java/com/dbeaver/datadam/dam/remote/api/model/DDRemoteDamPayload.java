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
package com.dbeaver.datadam.dam.remote.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable JSON-compatible values, independent of the serialization library. Numbers use exact decimals. */
public final class DDRemoteDamPayload {
    private DDRemoteDamPayload() {
    }

    @NotNull
    public static Map<String, Object> copyOf(@NotNull Map<?, ?> payload) {
        return copyMap(Objects.requireNonNull(payload), new IdentityHashMap<>());
    }

    @NotNull
    private static Map<String, Object> copyMap(@NotNull Map<?, ?> source, @NotNull IdentityHashMap<Object, Boolean> visiting) {
        enter(source, visiting);
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Expected a string payload key");
                }
                result.put(key, copyValue(entry.getValue(), visiting));
            }
            return Collections.unmodifiableMap(result);
        } finally {
            visiting.remove(source);
        }
    }

    @Nullable
    private static Object copyValue(@Nullable Object value, @NotNull IdentityHashMap<Object, Boolean> visiting) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
            || value instanceof BigInteger || value instanceof Float || value instanceof Double) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return copyMap(map, visiting);
        }
        if (value instanceof List<?> list) {
            enter(list, visiting);
            try {
                List<Object> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    result.add(copyValue(item, visiting));
                }
                return Collections.unmodifiableList(result);
            } finally {
                visiting.remove(list);
            }
        }
        throw new IllegalArgumentException("Expected a JSON-compatible payload value");
    }

    private static void enter(@NotNull Object value, @NotNull IdentityHashMap<Object, Boolean> visiting) {
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic payload");
        }
    }
}
