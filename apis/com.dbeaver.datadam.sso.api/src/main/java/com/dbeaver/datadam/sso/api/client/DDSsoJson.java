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
package com.dbeaver.datadam.sso.api.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Strict JWT/JWKS parsing: Gson's normal tree reader silently replaces duplicate members. */
final class DDSsoJson {
    private static final int MAX_DEPTH = 32;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

    private DDSsoJson() {
    }

    @NotNull
    static JsonObject object(@NotNull String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0);
            if (!(value instanceof JsonObject object) || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Expected one JSON object");
            }
            return object;
        }
    }

    @NotNull
    static String utf8(@NotNull byte[] bytes) throws CharacterCodingException {
        // CharsetDecoder reports malformed UTF-8 instead of replacing bytes inside signed claims.
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    @NotNull
    static String text(@Nullable JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString() || primitive.getAsString().isBlank()) {
            throw new IllegalArgumentException();
        }
        return primitive.getAsString();
    }

    @NotNull
    static byte[] base64Url(@NotNull String encoded) {
        if (!BASE64URL.matcher(encoded).matches()) {
            throw new IllegalArgumentException();
        }
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
            throw new IllegalArgumentException();
        }
        return decoded;
    }

    @NotNull
    private static JsonElement read(@NotNull JsonReader reader, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("SSO JSON nesting is too deep");
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) {
                        throw new IllegalArgumentException("Duplicate SSO JSON member");
                    }
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(ToNumberPolicy.LAZILY_PARSED_NUMBER.readNumber(reader));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Invalid SSO JSON value");
        };
    }
}
