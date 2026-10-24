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
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

abstract class DDExternalDamGraphQlClient extends AbstractRestClient {
    private final String serviceSecret;
    private final String endpoint;
    private final Duration requestTimeout;

    DDExternalDamGraphQlClient(@NotNull String serviceUrl, @NotNull String serviceSecret, @NotNull String endpoint, int timeoutMillis) {
        super(validateServiceUrl(serviceUrl), Math.min(DEFAULT_CONNECT_TIMEOUT, timeoutMillis), timeoutMillis,
            List.of(), HttpClient.Redirect.NEVER);
        if (!DDExternalDamApi.isValidServiceSecret(serviceSecret)) {
            throw new IllegalArgumentException("A valid External DAM service secret is required");
        }
        this.serviceSecret = serviceSecret;
        this.endpoint = endpoint;
        this.requestTimeout = Duration.ofMillis(timeoutMillis > 0 ? timeoutMillis : DEFAULT_READ_TIMEOUT);
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
            response = execute(createRequest(operationName, query, variables), String.class);
        } catch (DBException e) {
            throw requestFailure(e);
        }
        return readResult(response, field);
    }

    @NotNull
    final CompletionStage<JsonElement> requestAsync(
        @NotNull String operationName,
        @NotNull String query,
        @NotNull Map<String, ?> variables,
        @NotNull String field
    ) {
        try {
            HttpRequest request = createRequest(operationName, query, variables).build();
            var exchange = getHttpClient().sendAsync(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
            var bounded = exchange.copy().orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            var completed = bounded.whenComplete((response, error) -> {
                if (error instanceof TimeoutException || bounded.isCancelled()) {
                    exchange.cancel(true);
                }
            });
            return propagateCancellation(completed.handle((response, error) -> {
                if (error != null) {
                    throw new CompletionException(requestFailure(error));
                }
                try {
                    int code = response.statusCode();
                    if (code < 200 || code >= 300) {
                        throw mapErrorResponse(code, "", request.uri());
                    }
                    return readResult(response.body(), field);
                } catch (DDExternalDamException e) {
                    throw new CompletionException(e);
                }
            }), bounded);
        } catch (DBException | RuntimeException e) {
            return CompletableFuture.failedFuture(requestFailure(e));
        }
    }

    @NotNull
    final <T> CompletionStage<T> requestAsync(
        @NotNull String operationName,
        @NotNull String query,
        @NotNull Map<String, ?> variables,
        @NotNull String field,
        @NotNull ResultReader<T> reader
    ) {
        CompletableFuture<JsonElement> response = requestAsync(operationName, query, variables, field).toCompletableFuture();
        return propagateCancellation(response.thenApply(result -> {
            try {
                return reader.read(result);
            } catch (DDExternalDamException e) {
                throw new CompletionException(e);
            }
        }), response);
    }

    @NotNull
    private static <T> CompletionStage<T> propagateCancellation(
        @NotNull CompletableFuture<T> result,
        @NotNull CompletableFuture<?> source
    ) {
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                source.cancel(true);
            }
        });
        return result;
    }

    @NotNull
    private HttpRequest.Builder createRequest(
        @NotNull String operationName,
        @NotNull String query,
        @NotNull Map<String, ?> variables
    ) throws DBException {
        return HttpRequest.newBuilder(buildUri(CommonUtils.removeLeadingSlash(endpoint), Map.of()))
            .timeout(requestTimeout)
            .header(DDExternalDamApi.HEADER_SERVICE_TOKEN, serviceSecret)
            .header(HttpConstants.HEADER_CONTENT_TYPE, MediaType.JSON.toString())
            .header(HttpConstants.HEADER_ACCEPT, MediaType.JSON.toString())
            .header(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_CONTROL_NO_STORE)
            .POST(createBodyPublisher(Map.of("operationName", operationName, "query", query, "variables", variables), MediaType.JSON));
    }

    @NotNull
    private static JsonElement readResult(@NotNull String response, @NotNull String field) throws DDExternalDamException {
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
    protected DDExternalDamException mapErrorResponse(int code, @NotNull String message, @NotNull URI uri) {
        return new DDExternalDamException("External DAM request failed: HTTP " + code);
    }

    static boolean readBoolean(@NotNull JsonElement value) throws DDExternalDamException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalidResponse();
        }
        return value.getAsBoolean();
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
    private static DDExternalDamException requestFailure(@NotNull Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof TimeoutException) {
            HttpTimeoutException timeout = new HttpTimeoutException("External DAM service request timed out");
            timeout.initCause(error);
            error = timeout;
        }
        return error instanceof DDExternalDamException failure
            ? failure : new DDExternalDamException("External DAM service request failed", error);
    }

    @NotNull
    private static String validateServiceUrl(@NotNull String serviceUrl) {
        if (!DDExternalDamApi.isValidServiceUrl(serviceUrl)) {
            throw new IllegalArgumentException("Invalid External DAM service URL");
        }
        return serviceUrl;
    }

    @FunctionalInterface
    interface ResultReader<T> {
        T read(@NotNull JsonElement result) throws DDExternalDamException;
    }
}
