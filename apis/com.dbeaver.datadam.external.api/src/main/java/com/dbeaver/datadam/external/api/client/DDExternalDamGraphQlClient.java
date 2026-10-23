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
import java.util.List;
import java.util.Map;

abstract class DDExternalDamGraphQlClient extends AbstractRestClient {
    private final String serviceSecret;
    private final String endpoint;

    DDExternalDamGraphQlClient(@NotNull String serviceUrl, @NotNull String serviceSecret, @NotNull String endpoint, int timeoutMillis) {
        super(validateServiceUrl(serviceUrl), Math.min(DEFAULT_CONNECT_TIMEOUT, timeoutMillis), timeoutMillis,
            List.of(), HttpClient.Redirect.NEVER);
        if (!DDExternalDamApi.isValidServiceSecret(serviceSecret)) {
            throw new IllegalArgumentException("A valid External DAM service secret is required");
        }
        this.serviceSecret = serviceSecret;
        this.endpoint = endpoint;
    }

    @NotNull
    final JsonElement request(
        @NotNull String operationName,
        @NotNull String query,
        @NotNull Map<String, ?> variables,
        @NotNull String field
    ) throws DDExternalDamException {
        String response;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(buildUri(CommonUtils.removeLeadingSlash(endpoint), Map.of()))
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
    static JsonObject readObject(@Nullable JsonElement value) throws DDExternalDamException {
        if (value == null || !value.isJsonObject()) {
            throw invalidResponse();
        }
        return value.getAsJsonObject();
    }

    @NotNull
    static DDExternalDamException invalidResponse() {
        return new DDExternalDamException("Invalid External DAM response");
    }

    @NotNull
    private static String validateServiceUrl(@NotNull String serviceUrl) {
        if (!DDExternalDamApi.isValidServiceUrl(serviceUrl)) {
            throw new IllegalArgumentException("Invalid External DAM service URL");
        }
        return serviceUrl;
    }
}
