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
package com.dbeaver.datadam.external.api;

import com.dbeaver.datadam.external.api.model.DDHello;
import com.dbeaver.datadam.external.api.model.DDMessage;
import com.dbeaver.datadam.external.api.model.DDReady;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DDExternalDamProtocolTest {
    @Test
    void roundTripsApplicationMessagesWithTopicTypeAndPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        DDMessage message = new DDMessage("projects", "project.updated", payload);
        String encoded = DDExternalDamProtocol.encode(message);

        assertEquals(JsonParser.parseString("""
            {"topic":"projects","type":"project.updated","payload":{"projectId":"project-1"}}
            """), JsonParser.parseString(encoded));
        assertEquals(message, DDExternalDamProtocol.decode(encoded));
    }

    @Test
    void roundTripsHelloWithoutAccountCredentialsOrProtocolVersion() {
        DDHello hello = new DDHello("DI_desktop", "run-1", "DBeaver", "26.2.4");
        String encoded = DDExternalDamProtocol.encodeHello(hello);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(hello, DDExternalDamProtocol.decodeHello(encoded));
        assertEquals("hello", envelope.get("type").getAsString());
        assertEquals(2, envelope.size());
        assertEquals(4, envelope.getAsJsonObject("payload").size());
        assertFalse(encoded.contains("accountId"));
        assertFalse(encoded.contains("accessToken"));
        assertFalse(encoded.contains("protocolVersion"));
    }

    @Test
    void roundTripsReadyWithOnlyTheAssignedConnectionId() {
        DDReady ready = new DDReady("connection-1");
        String encoded = DDExternalDamProtocol.encodeReady(ready);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(ready, DDExternalDamProtocol.decodeReady(encoded));
        assertEquals("ready", envelope.get("type").getAsString());
        assertEquals(1, envelope.getAsJsonObject("payload").size());
    }

    @Test
    void keepsControlMessagesSeparateFromTopicMessages() {
        String hello = DDExternalDamProtocol.encodeHello(new DDHello("DI_desktop", "run-1", "DBeaver", "26.2.4"));
        String ready = DDExternalDamProtocol.encodeReady(new DDReady("connection-1"));

        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decode(hello));
        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decode(ready));
        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeHello(ready));
        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeReady(hello));
        String event = """
            {"topic":"projects","type":"project.updated","payload":{}}
            """;
        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeHello(event));
        assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeReady(event));
    }

    @Test
    void preservesUnknownApplicationTypesAndAdditionalEnvelopeFields() {
        DDMessage message = DDExternalDamProtocol.decode("""
            {"topic":"future","type":"future.event","payload":{"value":42},"additional":true}
            """);

        assertEquals("future", message.topic());
        assertEquals("future.event", message.type());
        assertEquals(42, message.payload().get("value").getAsInt());
    }

    @Test
    void rejectsMalformedEnvelopesAndMissingFields() {
        for (String message : List.of(
            "", "null", "[]", "true", "42", "{", "{}",
            "{\"topic\":\"projects\",\"type\":\"updated\"}",
            "{\"topic\":\"projects\",\"payload\":{}}",
            "{\"type\":\"updated\",\"payload\":{}}"
        )) {
            assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decode(message), message);
        }
    }

    @Test
    void rejectsNonStringAndBlankTopicAndTypeFields() {
        for (String field : List.of("topic", "type")) {
            for (String value : List.of("null", "true", "42", "[]", "{}", "\"\"", "\" \"")) {
                JsonObject envelope = JsonParser.parseString("""
                    {"topic":"projects","type":"updated","payload":{}}
                    """).getAsJsonObject();
                envelope.add(field, JsonParser.parseString(value));

                assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decode(envelope.toString()));
            }
        }
    }

    @Test
    void rejectsNonObjectPayloads() {
        for (String payload : List.of("null", "true", "42", "[]", "\"value\"")) {
            String message = "{\"topic\":\"projects\",\"type\":\"updated\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decode(message));
        }
    }

    @Test
    void rejectsMissingAndIncorrectlyTypedHelloFields() {
        String valid = DDExternalDamProtocol.encodeHello(new DDHello("DI_desktop", "run-1", "DBeaver", "26.2.4"));
        for (String field : List.of("deploymentId", "applicationRunId", "product", "version")) {
            JsonObject missing = JsonParser.parseString(valid).getAsJsonObject();
            missing.getAsJsonObject("payload").remove(field);
            assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeHello(missing.toString()));

            for (String value : List.of("null", "true", "42", "[]", "{}", "\" \"")) {
                JsonObject invalid = JsonParser.parseString(valid).getAsJsonObject();
                invalid.getAsJsonObject("payload").add(field, JsonParser.parseString(value));

                assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeHello(invalid.toString()));
            }
        }
    }

    @Test
    void rejectsReadyWithoutAStringConnectionId() {
        for (String payload : List.of("{}", "{\"connectionId\":null}", "{\"connectionId\":42}", "{\"connectionId\":\" \"}")) {
            String message = "{\"type\":\"ready\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDExternalDamProtocol.decodeReady(message));
        }
    }
}
