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
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
            "DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4");
        String encoded = DDRemoteDamProtocol.encodeHandshakeRequest(hello);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(hello, DDRemoteDamProtocol.decodeHandshakeRequest(encoded));
        assertEquals("hello", envelope.get("type").getAsString());
        assertEquals(2, envelope.size());
        assertEquals(6, envelope.getAsJsonObject("payload").size());
        assertEquals("workspace-1", envelope.getAsJsonObject("payload").get("workspaceId").getAsString());
        assertEquals("client-dam_1", envelope.getAsJsonObject("payload").get("clientDamId").getAsString());
        assertFalse(encoded.contains("accountId"));
        assertFalse(encoded.contains("accessToken"));
        assertFalse(encoded.contains("protocolVersion"));
    }

    @Test
    void acceptsWorkspaceIdLengthBoundariesAndRejectsNullBlankAndOversizedIds() {
        for (String workspaceId : List.of("w", "w".repeat(256))) {
            DDRemoteDamHandshakeRequest hello = new DDRemoteDamHandshakeRequest(
                "DI_remoteDam", "run-1", workspaceId, "client-dam_1", "DBeaver", "26.2.4");

            assertEquals(hello, DDRemoteDamProtocol.decodeHandshakeRequest(DDRemoteDamProtocol.encodeHandshakeRequest(hello)));
        }
        for (String workspaceId : new String[] {null, "", " ", "\t\n", "w".repeat(257)}) {
            assertThrows(IllegalArgumentException.class, () ->
                new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", workspaceId, "client-dam_1", "DBeaver", "26.2.4"));
        }
        JsonObject oversized = JsonParser.parseString(DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4")))
            .getAsJsonObject();
        oversized.getAsJsonObject("payload").addProperty("workspaceId", "w".repeat(257));

        assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(oversized.toString()));
    }

    @Test
    void acceptsClientDamIdAlphabetAndLengthBoundaries() {
        for (String clientDamId : List.of(
            "A", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-", "a".repeat(2048)
        )) {
            DDRemoteDamHandshakeRequest hello = new DDRemoteDamHandshakeRequest(
                "DI_remoteDam", "run-1", "workspace-1", clientDamId, "DBeaver", "26.2.4");

            assertEquals(hello, DDRemoteDamProtocol.decodeHandshakeRequest(DDRemoteDamProtocol.encodeHandshakeRequest(hello)));
        }
    }

    @Test
    void rejectsNullBlankOversizedAndNonUrlSafeClientDamIds() {
        String valid = DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4"));
        for (String clientDamId : new String[] {
            null, "", " ", "\t\n", "a".repeat(2049), "a+b", "a/b", "YQ=", "YQ==", "a b", "a\nb", "a:b", "a.b", "é"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", clientDamId, "DBeaver", "26.2.4"));
            JsonObject invalid = JsonParser.parseString(valid).getAsJsonObject();
            invalid.getAsJsonObject("payload").addProperty("clientDamId", clientDamId);

            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(invalid.toString()));
        }
    }

    @Test
    void roundTripsHandshakeResponseWithAllSettings() {
        DDRemoteDamHandshakeResponse ready = new DDRemoteDamHandshakeResponse("session-1",
            new DDRemoteDamSettings(Map.of("feature", "false"), Map.of("label", "Desktop")));
        String encoded = DDRemoteDamProtocol.encodeHandshakeResponse(ready);
        JsonObject envelope = JsonParser.parseString(encoded).getAsJsonObject();

        assertEquals(ready, DDRemoteDamProtocol.decodeHandshakeResponse(encoded));
        assertEquals("ready", envelope.get("type").getAsString());
        assertEquals(2, envelope.getAsJsonObject("payload").size());
    }

    @Test
    void missingSettingsFromOlderBrokerDefaultToEmpty() {
        assertEquals(DDRemoteDamSettings.EMPTY, DDRemoteDamProtocol.decodeHandshakeResponse(
            "{\"type\":\"ready\",\"payload\":{\"damSessionId\":\"session\"}}").settings());
        assertFalse(DDRemoteDamSettings.EMPTY.isEnabled(DDRemoteDamSettingKeys.MCP_ENABLED, true, false));
    }

    @Test
    void rejectsMalformedInitialSettings() {
        for (String settings : List.of("null", "[]", "{}", "{\"account\":{},\"dam\":null}",
            "{\"account\":{},\"dam\":{\"feature\":true}}")) {
            String message = "{\"type\":\"ready\",\"payload\":{\"damSessionId\":\"session\",\"settings\":" + settings + "}}";
            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeResponse(message));
        }
    }

    @Test
    void keepsControlMessagesSeparateFromTopicMessages() {
        String hello = DDRemoteDamProtocol.encodeHandshakeRequest(
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4"));
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
                new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4")),
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
            new DDRemoteDamHandshakeRequest("DI_remoteDam", "run-1", "workspace-1", "client-dam_1", "DBeaver", "26.2.4"));
        for (String field : List.of("appDeploymentId", "appInstanceId", "workspaceId", "clientDamId", "product", "version")) {
            JsonObject missing = JsonParser.parseString(valid).getAsJsonObject();
            missing.getAsJsonObject("payload").remove(field);
            assertThrows(IllegalArgumentException.class, () -> DDRemoteDamProtocol.decodeHandshakeRequest(missing.toString()));
        }
        for (String field : List.of("appDeploymentId", "appInstanceId", "workspaceId", "clientDamId", "product", "version")) {
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
