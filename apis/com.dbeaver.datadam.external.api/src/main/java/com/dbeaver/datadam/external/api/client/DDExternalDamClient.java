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
package com.dbeaver.datadam.external.api.client;

import com.dbeaver.datadam.external.api.DDExternalDamApi;
import com.dbeaver.datadam.external.api.exception.DDExternalDamException;
import com.dbeaver.datadam.external.api.model.DDExternalDamConnection;
import com.dbeaver.datadam.external.api.model.DDMessage;
import com.dbeaver.rest.client.AbstractRestClient;
import com.dbeaver.rest.client.MediaType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DDExternalDamClient extends AbstractRestClient {
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String CONNECTIONS_FIELD = "externalDamConnections";
    private static final String SEND_FIELD = "sendExternalDamMessage";
    private static final String CONNECTIONS_OPERATION = "ExternalDamConnections";
    private static final String SEND_OPERATION = "SendExternalDamMessage";
    private static final String CONNECTIONS_QUERY = """
        query ExternalDamConnections($accountId: ID!) {
            externalDamConnections(accountId: $accountId) {
                deploymentId applicationRunId connectionId product version
            }
        }
        """;
    private static final String SEND_MUTATION = """
        mutation SendExternalDamMessage($accountId: ID!, $connectionId: ID!, $message: ExternalDamMessageInput!) {
            sendExternalDamMessage(accountId: $accountId, connectionId: $connectionId, message: $message)
        }
        """;

    private final String serviceSecret;

    public DDExternalDamClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        super(validateServiceUrl(serviceUrl), DEFAULT_CONNECT_TIMEOUT, READ_TIMEOUT_MILLIS, List.of(), HttpClient.Redirect.NEVER);
        if (!DDExternalDamApi.isValidServiceSecret(serviceSecret)) {
            throw new IllegalArgumentException("A valid External DAM service secret is required");
        }
        this.serviceSecret = serviceSecret;
    }

    @NotNull
    public List<DDExternalDamConnection> getConnections(@NotNull UUID accountId) throws DDExternalDamException {
        JsonElement result = request(CONNECTIONS_OPERATION, CONNECTIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), CONNECTIONS_FIELD);
        if (!result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDExternalDamConnection> connections = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            JsonObject connection = readObject(value);
            connections.add(new DDExternalDamConnection(
                readString(connection, "deploymentId"), readString(connection, "applicationRunId"),
                readString(connection, "connectionId"), readString(connection, "product"), readString(connection, "version")
            ));
        }
        return List.copyOf(connections);
    }

    public boolean sendMessage(@NotNull UUID accountId, @NotNull String connectionId, @NotNull DDMessage message)
            throws DDExternalDamException {
        if (connectionId.isBlank()) {
            throw new IllegalArgumentException("A connection ID is required");
        }
        JsonElement result = request(SEND_OPERATION, SEND_MUTATION, Map.of(
            "accountId", Objects.requireNonNull(accountId).toString(), "connectionId", connectionId,
            "message", Objects.requireNonNull(message)
        ), SEND_FIELD);
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isBoolean()) {
            throw invalidResponse();
        }
        return result.getAsBoolean();
    }

    @NotNull
    private JsonElement request(
        @NotNull String operationName,
        @NotNull String query,
        @NotNull Map<String, ?> variables,
        @NotNull String field
    ) throws DDExternalDamException {
        String response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                buildUri(CommonUtils.removeLeadingSlash(DDExternalDamApi.GRAPHQL_PATH), Map.of()))
                .header(DDExternalDamApi.HEADER_SERVICE_TOKEN, serviceSecret)
                .header(HttpConstants.HEADER_CONTENT_TYPE, MediaType.JSON.toString())
                .header(HttpConstants.HEADER_ACCEPT, MediaType.JSON.toString())
                .header(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_CONTROL_NO_STORE)
                .POST(createBodyPublisher(Map.of("operationName", operationName, "query", query, "variables", variables), MediaType.JSON));
            response = execute(request, String.class);
        } catch (DBException e) {
            if (e instanceof DDExternalDamException failure) {
                throw failure;
            }
            throw new DDExternalDamException("External DAM service request failed", e);
        }
        JsonObject envelope;
        try {
            envelope = readObject(JsonParser.parseString(response));
        } catch (JsonParseException e) {
            throw invalidResponse();
        }
        JsonElement errors = envelope.get("errors");
        if (errors != null && (!errors.isJsonArray() || !errors.getAsJsonArray().isEmpty())) {
            throw new DDExternalDamException("External DAM GraphQL request failed");
        }
        JsonElement result = readObject(envelope.get("data")).get(field);
        if (result == null || result.isJsonNull()) {
            throw invalidResponse();
        }
        return result;
    }

    @Override
    @NotNull
    protected DBException mapErrorResponse(int code, @NotNull String message, @NotNull URI uri) {
        return new DDExternalDamException("External DAM request failed: HTTP " + code);
    }

    @NotNull
    private static JsonObject readObject(@Nullable JsonElement value) throws DDExternalDamException {
        if (value == null || !value.isJsonObject()) {
            throw invalidResponse();
        }
        return value.getAsJsonObject();
    }

    @NotNull
    private static String readString(@NotNull JsonObject object, @NotNull String field) throws DDExternalDamException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalidResponse();
        }
        return value.getAsString();
    }

    @NotNull
    private static DDExternalDamException invalidResponse() {
        return new DDExternalDamException("Invalid External DAM response");
    }

    @NotNull
    private static String validateServiceUrl(@NotNull String serviceUrl) {
        try {
            URI uri = URI.create(serviceUrl);
            if (uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)) {
                return uri.toString();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid External DAM service URL");
        }
        throw new IllegalArgumentException("Invalid External DAM service URL");
    }
}
