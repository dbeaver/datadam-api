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
import com.dbeaver.datadam.external.api.model.DDConnectionInfo;
import com.dbeaver.datadam.external.api.model.DDMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DDExternalDamCallbackClientTest {
    private static final String SECRET = "external-dam-callback-secret-2026-test";
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("deployment", "run", "socket");
    private static final DDMessage MESSAGE = new DDMessage("projects", "updated",
        JsonParser.parseString("{\"nested\":{\"value\":42}}").getAsJsonObject());
    private final AtomicReference<String> response = new AtomicReference<>("{\"data\":{\"deliverExternalDamMessage\":true}}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger responseDelay = new AtomicInteger();
    private final AtomicReference<CapturedRequest> request = new AtomicReference<>();
    private HttpServer server;
    private DDExternalDamCallbackClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = new DDExternalDamCallbackClient(url(), SECRET);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void deliversTheExactCallbackWireWithIsolatedCredentialsAndAnOpaquePayload() throws DDExternalDamException {
        for (String suffix : List.of("", "/")) {
            client = new DDExternalDamCallbackClient(url() + suffix, SECRET);
            deliver();
            CapturedRequest captured = request.get();
            assertEquals("POST", captured.method());
            assertEquals("/internal/external-dam/graphql", captured.path());
            assertEquals(SECRET, captured.secret());
            assertEquals("application/json", captured.contentType());
            assertEquals("no-store", captured.cacheControl());
            JsonObject body = captured.body();
            assertEquals("DeliverExternalDamMessage", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("$connection: ExternalDamConnectionInfoInput!"));
            assertTrue(query.contains("$message: ExternalDamMessageInput!"));
            assertTrue(query.contains("deliverExternalDamMessage(subscriptionId: $subscriptionId, accountId: $accountId, "
                + "connection: $connection, message: $message)"));
            JsonObject variables = body.getAsJsonObject("variables");
            assertEquals(Set.of("subscriptionId", "accountId", "connection", "message"), variables.keySet());
            assertEquals(SUBSCRIPTION_ID.toString(), variables.get("subscriptionId").getAsString());
            assertEquals(ACCOUNT_ID.toString(), variables.get("accountId").getAsString());
            assertEquals(Set.of("deploymentId", "applicationRunId", "connectionId"), variables.getAsJsonObject("connection").keySet());
            assertEquals("socket", variables.getAsJsonObject("connection").get("connectionId").getAsString());
            assertEquals(MESSAGE.payload(), variables.getAsJsonObject("message").get("payload"));
            assertFalse(body.toString().contains(SECRET));
        }
    }

    @Test
    void rejectsFalseAcknowledgementsAsProtocolFailures() {
        response.set("{\"data\":{\"deliverExternalDamMessage\":false}}");
        assertFailureOnce();
    }

    @Test
    void rejectsMalformedEnvelopesPartialErrorsAndNonBooleanResultsWithoutRetries() {
        for (String body : List.of("", "{", "null", "[]", "{}", "{\"data\":null}", "{\"data\":{}}",
            "{\"data\":{\"deliverExternalDamMessage\":null}}", "{\"data\":{\"deliverExternalDamMessage\":\"true\"}}",
            "{\"data\":{\"deliverExternalDamMessage\":1}}", "{\"errors\":null}",
            "{\"data\":{\"deliverExternalDamMessage\":true},\"errors\":[{\"message\":\"" + SECRET + "\"}]}")) {
            response.set(body);
            assertFailureOnce();
        }
    }

    @Test
    void rejectsHttpFailuresAndNeverFollowsRedirects() {
        for (int code : List.of(301, 302, 303, 307, 308, 400, 401, 403, 429, 500, 503)) {
            status.set(code);
            assertFailureOnce();
        }
    }

    @Test
    void boundsDeliveryTimeAndDoesNotRetryTimedOutMessages() {
        responseDelay.set(1000);
        client = new DDExternalDamCallbackClient(url(), SECRET, Duration.ofMillis(250));
        assertFailureOnce();
    }

    @Test
    void rejectsUntrustedOriginFormsAndInvalidSecretsBeforeNetworkIo() {
        for (String origin : List.of("", "/relative", url() + "/prefix", url() + "?query=1", url() + "#fragment",
            "http://user:password@127.0.0.1", "ftp://127.0.0.1", "http://127.0.0.1:65536")) {
            assertThrows(IllegalArgumentException.class, () -> new DDExternalDamCallbackClient(origin, SECRET));
        }
        for (String secret : List.of("", "short", " ".repeat(32), "x".repeat(31) + "\n")) {
            assertThrows(IllegalArgumentException.class, () -> new DDExternalDamCallbackClient(url(), secret));
        }
        assertThrows(IllegalArgumentException.class, () -> new DDExternalDamCallbackClient(url(), SECRET, Duration.ZERO));
        assertEquals(0, requestCount.get());
    }

    private void deliver() throws DDExternalDamException {
        client.deliver(SUBSCRIPTION_ID, ACCOUNT_ID, CONNECTION, MESSAGE);
    }

    private void assertFailureOnce() {
        int before = requestCount.get();
        DDExternalDamException failure = assertThrows(DDExternalDamException.class, this::deliver);
        assertFalse(failure.toString().contains(SECRET));
        assertEquals(before + 1, requestCount.get());
    }

    @NotNull
    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(@NotNull HttpExchange exchange) throws IOException {
        try {
            request.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst(DDExternalDamApi.HEADER_SERVICE_TOKEN),
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Cache-Control"),
                JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()));
            requestCount.incrementAndGet();
            if (responseDelay.get() > 0) {
                Thread.sleep(responseDelay.get());
            }
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", url() + "/redirected");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private record CapturedRequest(
        @NotNull String method,
        @NotNull String path,
        @Nullable String secret,
        @Nullable String contentType,
        @Nullable String cacheControl,
        @NotNull JsonObject body
    ) {
    }
}
