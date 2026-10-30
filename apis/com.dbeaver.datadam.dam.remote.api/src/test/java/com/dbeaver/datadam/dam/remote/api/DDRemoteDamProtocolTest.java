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
package com.dbeaver.datadam.dam.remote.api;

import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamHandshakeRequest;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamHandshakeResponse;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DDRemoteDamProtocolTest {
    @Test
    void roundTripsApplicationMessagesWithTopicTypeAndPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        DDRemoteDamMessage message = new DDRemoteDamMessage("projects", "project.updated", payload);
        String encoded = DDRemoteDamProtocol.encode(message);

        assertEquals(JsonParser.parseString("""
            {"topic":"projects","type":"project.updated","payload":{"projectId":"project-1"}}
            """), JsonParser.parseString(encoded));
        assertEquals(message, DDRemoteDamProtocol.decode(encoded));
    }

    @Test
    void roundTripsHandshakeRequestWithoutAccountCredentialsOrProtocolVersion() {
        DDRemoteDamHandshakeRequest hello = new DDRemoteDamHandshakeRequest(
            "DI_remoteDam", "run-1", "workspace-1", "DBeaver", "26.2.4");
        String encoded = DDRemoteDamProtocol.encodeHandshakeRequest(hello);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(hello, DDRemoteDamProtocol.decodeHandshakeRequest(encoded));
        assertEquals("hello", envelope.get("type").getAsString());
        assertEquals(2, envelope.size());
        assertEquals(5, envelope.getAsJsonObject("payload").size());
        assertEquals("workspace-1", envelope.getAsJsonObject("payload").get("workspaceId").getAsString());
        assertFalse(encoded.contains("accountId"));
        assertFalse(encoded.contains("accessToken"));
        assertFalse(encoded.contains("protocolVersion"));
    }

    @Test
    void acceptsWorkspaceIdLengthBoundariesAndRejectsNullBlankAndOversizedIds() {
        for (String workspaceId : List.of("w", "w".repeat(256))) {
            DDRemoteDamHandshakeRequest hello = new DDRemoteDamHandshakeRequest(
                "DI_remoteDam", "run-1", workspaceId, "DBeaver", "26.2.4");

            assertEquals(hello, DDRemoteDamProtocol.decodeHandshakeRequest(DDRemoteDamProtocol.encodeHandshakeRequest(hello)));
        }
        for (String workspaceId : new String[] {null, "", " ", "\t\n", "w".repeat(257)}) {
            assertThrows(IllegalArgumentException.class, () ->
                new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", workspaceId, "DBeaver", "26.2.4"));
        }
        JsonObject oversized = JsonParser.parseString(DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "DBeaver", "26.2.4"))).getAsJsonObject();
        oversized.getAsJsonObject("payload").addProperty("workspaceId", "w".repeat(257));

        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(oversized.toString()));
    }

    @Test
    void roundTripsHandshakeResponseWithOnlyTheAssignedSessionId() {
        DDRemoteDamHandshakeResponse ready = new DDRemoteDamHandshakeResponse("session-1");
        String encoded = DDRemoteDamProtocol.encodeHandshakeResponse(ready);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(ready, DDRemoteDamProtocol.decodeHandshakeResponse(encoded));
        assertEquals("ready", envelope.get("type").getAsString());
        assertEquals(1, envelope.getAsJsonObject("payload").size());
    }

    @Test
    void keepsControlMessagesSeparateFromTopicMessages() {
        String hello = DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "DBeaver", "26.2.4"));
        String ready = DDRemoteDamProtocol.encodeHandshakeResponse(new DDRemoteDamHandshakeResponse("session-1"));

        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decode(hello));
        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decode(ready));
        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(ready));
        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeResponse(hello));
        String event = """
            {"topic":"projects","type":"project.updated","payload":{}}
            """;
        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(event));
        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeResponse(event));
    }

    @Test
    void rejectsControlMessagesWithAnyTopicField() {
        for (String control : List.of(
            DDRemoteDamProtocol.encodeHandshakeRequest(
                new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "DBeaver", "26.2.4")),
            DDRemoteDamProtocol.encodeHandshakeResponse(new DDRemoteDamHandshakeResponse("session-1"))
        )) {
            for (String topic : List.of("\"projects\"", "null", "\"\"", "\" \"", "42", "true", "[]", "{}")) {
                JsonObject envelope = JsonParser.parseString(control).getAsJsonObject();
                envelope.add("topic", JsonParser.parseString(topic));
                String message = envelope.toString();

                assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(message));
                assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeResponse(message));
            }
        }
    }

    @Test
    void allowsControlTypeNamesInApplicationMessages() {
        for (String type : List.of(DDRemoteDamProtocol.TYPE_HANDSHAKE_REQUEST, DDRemoteDamProtocol.TYPE_HANDSHAKE_RESPONSE)) {
            DDRemoteDamMessage message = new DDRemoteDamMessage("projects", type, new JsonObject());

            assertEquals(message, DDRemoteDamProtocol.decode(DDRemoteDamProtocol.encode(message)));
        }
    }

    @Test
    void preservesUnknownApplicationTypesAndAdditionalEnvelopeFields() {
        DDRemoteDamMessage message = DDRemoteDamProtocol.decode("""
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
            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decode(message), message);
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

                assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decode(envelope.toString()));
            }
        }
    }

    @Test
    void rejectsNonObjectPayloads() {
        for (String payload : List.of("null", "true", "42", "[]", "\"value\"")) {
            String message = "{\"topic\":\"projects\",\"type\":\"updated\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decode(message));
        }
    }

    @Test
    void rejectsMissingAndMalformedHandshakeRequestFields() {
        String valid = DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "DBeaver", "26.2.4"));
        for (String field : List.of("appDeploymentId", "appInstanceId", "workspaceId", "product", "version")) {
            JsonObject missing = JsonParser.parseString(valid).getAsJsonObject();
            missing.getAsJsonObject("payload").remove(field);
            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(missing.toString()));
        }
        for (String field : List.of("appDeploymentId", "appInstanceId", "workspaceId", "product", "version")) {
            for (String value : List.of("null", "true", "42", "[]", "{}", "\"\"", "\" \"", "\"\\t\\n\"")) {
                JsonObject invalid = JsonParser.parseString(valid).getAsJsonObject();
                invalid.getAsJsonObject("payload").add(field, JsonParser.parseString(value));

                assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(invalid.toString()));
            }
        }
    }

    @Test
    void rejectsHandshakeResponseWithoutAStringSessionId() {
        for (String payload : List.of("{}", "{\"damSessionId\":null}", "{\"damSessionId\":42}", "{\"damSessionId\":\" \"}")) {
            String message = "{\"type\":\"ready\",\"payload\":" + payload + "}";

            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeResponse(message));
        }
    }
}
