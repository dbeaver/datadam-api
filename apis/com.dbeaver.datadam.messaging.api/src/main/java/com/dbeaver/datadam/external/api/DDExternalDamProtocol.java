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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;

public final class DDExternalDamProtocol {
    public static final String WEBSOCKET_PATH = "/ws";
    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_READY = "ready";

    private static final String FIELD_TOPIC = "topic";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String FIELD_DEPLOYMENT_ID = "deploymentId";
    private static final String FIELD_APPLICATION_RUN_ID = "applicationRunId";
    private static final String FIELD_PRODUCT = "product";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_CONNECTION_ID = "connectionId";

    private DDExternalDamProtocol() {
    }

    @NotNull
    public static String encode(@NotNull DDMessage message) {
        JsonObject envelope = envelope(message.type(), message.payload());
        envelope.addProperty(FIELD_TOPIC, message.topic());
        return envelope.toString();
    }

    @NotNull
    public static DDMessage decode(@NotNull String message) {
        JsonObject envelope = parseEnvelope(message);
        return new DDMessage(
            readString(envelope, FIELD_TOPIC),
            readString(envelope, FIELD_TYPE),
            readPayload(envelope)
        );
    }

    @NotNull
    public static String encodeHello(@NotNull DDHello hello) {
        JsonObject payload = new JsonObject();
        payload.addProperty(FIELD_DEPLOYMENT_ID, hello.deploymentId());
        payload.addProperty(FIELD_APPLICATION_RUN_ID, hello.applicationRunId());
        payload.addProperty(FIELD_PRODUCT, hello.product());
        payload.addProperty(FIELD_VERSION, hello.version());
        return envelope(TYPE_HELLO, payload).toString();
    }

    @NotNull
    public static DDHello decodeHello(@NotNull String message) {
        JsonObject payload = readControlPayload(message, TYPE_HELLO);
        return new DDHello(
            readString(payload, FIELD_DEPLOYMENT_ID),
            readString(payload, FIELD_APPLICATION_RUN_ID),
            readString(payload, FIELD_PRODUCT),
            readString(payload, FIELD_VERSION)
        );
    }

    @NotNull
    public static String encodeReady(@NotNull DDReady ready) {
        JsonObject payload = new JsonObject();
        payload.addProperty(FIELD_CONNECTION_ID, ready.connectionId());
        return envelope(TYPE_READY, payload).toString();
    }

    @NotNull
    public static DDReady decodeReady(@NotNull String message) {
        return new DDReady(readString(readControlPayload(message, TYPE_READY), FIELD_CONNECTION_ID));
    }

    @NotNull
    private static JsonObject envelope(@NotNull String type, @NotNull JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(FIELD_TYPE, type);
        envelope.add(FIELD_PAYLOAD, payload);
        return envelope;
    }

    @NotNull
    private static JsonObject parseEnvelope(@NotNull String message) {
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Expected a message object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid message JSON", e);
        }
    }

    @NotNull
    private static JsonObject readControlPayload(@NotNull String message, @NotNull String expectedType) {
        JsonObject envelope = parseEnvelope(message);
        if (envelope.has(FIELD_TOPIC) || !expectedType.equals(readString(envelope, FIELD_TYPE))) {
            throw new IllegalArgumentException("Expected a " + expectedType + " message");
        }
        return readPayload(envelope);
    }

    @NotNull
    private static JsonObject readPayload(@NotNull JsonObject envelope) {
        JsonElement payload = envelope.get(FIELD_PAYLOAD);
        if (payload == null || !payload.isJsonObject()) {
            throw new IllegalArgumentException("Expected a payload object");
        }
        return payload.getAsJsonObject();
    }

    @NotNull
    private static String readString(@NotNull JsonObject object, @NotNull String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Expected a non-blank string for " + field);
        }
        return value.getAsString();
    }
}
