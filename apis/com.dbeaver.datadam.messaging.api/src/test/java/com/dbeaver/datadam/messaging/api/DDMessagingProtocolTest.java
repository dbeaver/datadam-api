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
package com.dbeaver.datadam.messaging.api;

import com.dbeaver.datadam.messaging.api.model.DDMessagingHandshakeRequest;
import com.dbeaver.datadam.messaging.api.model.DDMessagingHandshakeResponse;
import com.dbeaver.datadam.messaging.api.model.DDMessagingMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DDMessagingProtocolTest {
    @Test
    void roundTripsApplicationMessagesWithTopicTypeAndPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        DDMessagingMessage message = new DDMessagingMessage("projects", "project.updated", payload);
        String encoded = DDMessagingProtocol.encode(message);

        assertEquals(JsonParser.parseString("""
            {"topic":"projects","type":"project.updated","payload":{"projectId":"project-1"}}
            """), JsonParser.parseString(encoded));
        assertEquals(message, DDMessagingProtocol.decode(encoded));
    }

    @Test
    void roundTripsHandshakeRequestWithoutAccountCredentialsOrProtocolVersion() {
        DDMessagingHandshakeRequest hello = new DDMessagingHandshakeRequest("DI_desktop", "run-1", "DBeaver", "26.2.4");
        String encoded = DDMessagingProtocol.encodeHandshakeRequest(hello);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(hello, DDMessagingProtocol.decodeHandshakeRequest(encoded));
        assertEquals("hello", envelope.get("type").getAsString());
        assertEquals(2, envelope.size());
        assertEquals(4, envelope.getAsJsonObject("payload").size());
        assertFalse(encoded.contains("accountId"));
        assertFalse(encoded.contains("accessToken"));
        assertFalse(encoded.contains("protocolVersion"));
    }

    @Test
    void roundTripsHandshakeResponseWithOnlyTheAssignedConnectionId() {
        DDMessagingHandshakeResponse ready = new DDMessagingHandshakeResponse("connection-1");
        String encoded = DDMessagingProtocol.encodeHandshakeResponse(ready);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(ready, DDMessagingProtocol.decodeHandshakeResponse(encoded));
        assertEquals("ready", envelope.get("type").getAsString());
        assertEquals(1, envelope.getAsJsonObject("payload").size());
    }

    @Test
    void keepsControlMessagesSeparateFromTopicMessages() {
        String hello = DDMessagingProtocol.encodeHandshakeRequest(
            new DDMessagingHandshakeRequest("DI_desktop", "run-1", "DBeaver", "26.2.4"));
        String ready = DDMessagingProtocol.encodeHandshakeResponse(new DDMessagingHandshakeResponse("connection-1"));

        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decode(hello));
        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decode(ready));
        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeRequest(ready));
        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeResponse(hello));
        String event = """
            {"topic":"projects","type":"project.updated","payload":{}}
            """;
        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeRequest(event));
        assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeResponse(event));
    }

    @Test
    void rejectsControlMessagesWithAnyTopicField() {
        for (String control : List.of(
            DDMessagingProtocol.encodeHandshakeRequest(new DDMessagingHandshakeRequest("DI_desktop", "run-1", "DBeaver", "26.2.4")),
            DDMessagingProtocol.encodeHandshakeResponse(new DDMessagingHandshakeResponse("connection-1"))
        )) {
            for (String topic : List.of("\"projects\"", "null", "\"\"", "\" \"", "42", "true", "[]", "{}")) {
                JsonObject envelope = JsonParser.parseString(control).getAsJsonObject();
                envelope.add("topic", JsonParser.parseString(topic));
                String message = envelope.toString();

                assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeRequest(message));
                assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeResponse(message));
            }
        }
    }

    @Test
    void allowsControlTypeNamesInApplicationMessages() {
        for (String type : List.of(DDMessagingProtocol.TYPE_HANDSHAKE_REQUEST, DDMessagingProtocol.TYPE_HANDSHAKE_RESPONSE)) {
            DDMessagingMessage message = new DDMessagingMessage("projects", type, new JsonObject());

            assertEquals(message, DDMessagingProtocol.decode(DDMessagingProtocol.encode(message)));
        }
    }

    @Test
    void preservesUnknownApplicationTypesAndAdditionalEnvelopeFields() {
        DDMessagingMessage message = DDMessagingProtocol.decode("""
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
            assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decode(message), message);
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

                assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decode(envelope.toString()));
            }
        }
    }

    @Test
    void rejectsNonObjectPayloads() {
        for (String payload : List.of("null", "true", "42", "[]", "\"value\"")) {
            String message = "{\"topic\":\"projects\",\"type\":\"updated\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decode(message));
        }
    }

    @Test
    void rejectsMissingAndIncorrectlyTypedHandshakeRequestFields() {
        String valid = DDMessagingProtocol.encodeHandshakeRequest(
            new DDMessagingHandshakeRequest("DI_desktop", "run-1", "DBeaver", "26.2.4"));
        for (String field : List.of("deploymentId", "applicationRunId", "product", "version")) {
            JsonObject missing = JsonParser.parseString(valid).getAsJsonObject();
            missing.getAsJsonObject("payload").remove(field);
            assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeRequest(missing.toString()));

            for (String value : List.of("null", "true", "42", "[]", "{}", "\" \"")) {
                JsonObject invalid = JsonParser.parseString(valid).getAsJsonObject();
                invalid.getAsJsonObject("payload").add(field, JsonParser.parseString(value));

                assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeRequest(invalid.toString()));
            }
        }
    }

    @Test
    void rejectsHandshakeResponseWithoutAStringConnectionId() {
        for (String payload : List.of("{}", "{\"connectionId\":null}", "{\"connectionId\":42}", "{\"connectionId\":\" \"}")) {
            String message = "{\"type\":\"ready\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDMessagingProtocol.decodeHandshakeResponse(message));
        }
    }
}
