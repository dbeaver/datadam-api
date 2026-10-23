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
package com.dbeaver.datadam.external.api.rpc;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;

class DDGsonRpcCodecTest {
    private final DDGsonRpcCodec codec = new DDGsonRpcCodec(new Gson());

    @Test
    void encodesOrdinaryAndPrimitiveValues() {
        Assertions.assertEquals(new JsonPrimitive("Hello"), codec.encode("Hello", String.class));
        Assertions.assertEquals("Hello", codec.decode(new JsonPrimitive("Hello"), String.class));
        Assertions.assertEquals(new JsonPrimitive(2), codec.encode(2, int.class));
        Assertions.assertEquals(2, codec.decode(new JsonPrimitive(2), int.class));
    }

    @Test
    void normalizesJsonNullAndSupportsNullableValues() {
        Assertions.assertNull(codec.encode(null, String.class));
        Assertions.assertNull(codec.encode(JsonNull.INSTANCE, JsonElement.class));
        Assertions.assertNull(codec.decode(null, String.class));
        Assertions.assertNull(codec.decode(JsonNull.INSTANCE, String.class));
    }

    @Test
    void usesDeclaredGenericTypesForApplicationRecords() {
        Type type = new TypeToken<List<Item>>() { }.getType();
        List<Item> values = List.of(new Item("Hello", List.of(1, 2)));

        Object decoded = codec.decode(codec.encode(values, type), type);

        Assertions.assertEquals(values, decoded);
        Assertions.assertInstanceOf(Item.class, ((List<?>) decoded).getFirst());
    }

    @Test
    void preservesApplicationGsonConfiguration() {
        var configured = new DDGsonRpcCodec(new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create());
        Item value = new Item("configured", List.of(3));

        JsonElement encoded = configured.encode(value, Item.class);

        Assertions.assertEquals("configured", encoded.getAsJsonObject().get("message_text").getAsString());
        Assertions.assertEquals(value, configured.decode(encoded, Item.class));
    }

    @Test
    void requiresAnExplicitGsonAndDeclaredTypes() {
        Assertions.assertThrows(NullPointerException.class, () -> new DDGsonRpcCodec(null));
        Assertions.assertThrows(NullPointerException.class, () -> codec.encode("Hello", null));
        Assertions.assertThrows(NullPointerException.class, () -> codec.decode(null, null));
    }

    private record Item(String messageText, List<Integer> numbers) {
    }
}
