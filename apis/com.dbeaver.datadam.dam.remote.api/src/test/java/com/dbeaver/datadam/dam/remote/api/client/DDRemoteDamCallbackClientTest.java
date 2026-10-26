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
package com.dbeaver.datadam.dam.remote.api.client;

import com.dbeaver.datadam.dam.remote.api.DDRemoteDamApi;
import com.dbeaver.datadam.dam.remote.api.exception.DDRemoteDamException;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamMessage;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSessionInfo;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DDRemoteDamCallbackClientTest {
    private static final String SECRET = "remote-dam-callback-secret-2026-test";
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final DDRemoteDamSessionInfo SESSION = new DDRemoteDamSessionInfo("deployment", "run", "socket");
    private static final DDRemoteDamMessage MESSAGE = new DDRemoteDamMessage("projects", "updated",
        JsonParser.parseString("""
            {"nested":{"value":42},"nothing":null,"integer":9007199254740993,
             "decimal":1.2345678901234567890123456789,"text":"Привет"}
            """).getAsJsonObject());
    private final AtomicReference<String> response = new AtomicReference<>("{\"data\":{\"deliverRemoteDamMessage\":true}}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger responseDelay = new AtomicInteger();
    private final AtomicReference<CapturedRequest> request = new AtomicReference<>();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> responseGate = new AtomicReference<>();
    private HttpServer server;
    private DDRemoteDamCallbackClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = new DDRemoteDamCallbackClient(url(), SECRET);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void deliversTheExactCallbackWireWithIsolatedCredentialsAndAnOpaquePayload() throws Exception {
        for (String suffix : List.of("", "/")) {
            client = new DDRemoteDamCallbackClient(url() + suffix, SECRET);
            deliver();
            CapturedRequest synchronous = request.get();
            assertNull(deliverAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
            CapturedRequest captured = request.get();
            assertEquals(synchronous, captured);
            assertEquals("POST", captured.method());
            assertEquals("/internal/remote-dam/graphql", captured.path());
            assertEquals(SECRET, captured.secret());
            assertEquals("application/json", captured.contentType());
            assertEquals("no-store", captured.cacheControl());
            JsonObject body = captured.body();
            assertEquals("DeliverRemoteDamMessage", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("$session: RemoteDamSessionInfoInput!"));
            assertTrue(query.contains("$message: RemoteDamMessageInput!"));
            assertTrue(query.contains("deliverRemoteDamMessage(subscriptionId: $subscriptionId, accountId: $accountId, "
                + "session: $session, message: $message)"));
            JsonObject variables = body.getAsJsonObject("variables");
            assertEquals(Set.of("subscriptionId", "accountId", "session", "message"), variables.keySet());
            assertEquals(SUBSCRIPTION_ID.toString(), variables.get("subscriptionId").getAsString());
            assertEquals(ACCOUNT_ID.toString(), variables.get("accountId").getAsString());
            assertEquals(Set.of("appDeploymentId", "appInstanceId", "damSessionId"), variables.getAsJsonObject("session").keySet());
            assertEquals("socket", variables.getAsJsonObject("session").get("damSessionId").getAsString());
            assertEquals(MESSAGE.payload(), variables.getAsJsonObject("message").get("payload"));
            JsonObject payload = variables.getAsJsonObject("message").getAsJsonObject("payload");
            assertEquals("9007199254740993", payload.get("integer").getAsString());
            assertEquals("1.2345678901234567890123456789", payload.get("decimal").getAsString());
            assertTrue(payload.get("nothing").isJsonNull());
            assertFalse(body.toString().contains(SECRET));
        }
        assertEquals(4, requestCount.get());
    }

    @Test
    void returnsBeforeTheCallbackHandlerCompletesAndWaitsForItsResponse() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<CompletionStage<Void>> stage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        responseGate.set(release);
        Thread caller = new Thread(() -> {
            try {
                stage.set(deliverAsync());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                returned.countDown();
            }
        });
        caller.start();
        try {
            assertTrue(requestReceived.await(5, TimeUnit.SECONDS));
            assertTrue(returned.await(1, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(stage.get());
            assertFalse(stage.get().toCompletableFuture().isDone());
            release.countDown();
            assertNull(stage.get().toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, requestCount.get());
        } finally {
            release.countDown();
            caller.join(5000);
        }
    }

    @Test
    void allowsDefaultAsyncDeliveryToWaitLongerThanFiveSecondsForTheHandler() throws Exception {
        responseDelay.set(5500);

        assertNull(deliverAsync().toCompletableFuture().get(8, TimeUnit.SECONDS));

        assertEquals(1, requestCount.get());
    }

    @Test
    void rejectsFalseAcknowledgementsAsProtocolFailures() {
        response.set("{\"data\":{\"deliverRemoteDamMessage\":false}}");
        assertFailureOnce();
        DDRemoteDamException failure = assertAsyncFailureOnce();
        assertEquals("Remote DAM callback was not accepted", failure.getMessage());
    }

    @Test
    void rejectsMalformedEnvelopesPartialErrorsAndNonBooleanResultsWithoutRetries() {
        for (String body : List.of("", "{", "null", "[]", "{}", "{\"data\":null}", "{\"data\":{}}",
            "{\"data\":{\"deliverRemoteDamMessage\":null}}", "{\"data\":{\"deliverRemoteDamMessage\":\"true\"}}",
            "{\"data\":{\"deliverRemoteDamMessage\":1}}", "{\"errors\":null}", "{\"" + SECRET + "\":[",
            "{\"data\":{\"deliverRemoteDamMessage\":true},\"errors\":[{\"message\":\"" + SECRET + "\"}]}")) {
            response.set(body);
            assertFailureOnce();
            assertAsyncFailureOnce();
        }
    }

    @Test
    void rejectsHttpFailuresAndNeverFollowsRedirects() {
        for (int code : List.of(301, 302, 303, 307, 308, 400, 401, 403, 429, 500, 503)) {
            status.set(code);
            assertFailureOnce();
            DDRemoteDamException failure = assertAsyncFailureOnce();
            assertEquals("Remote DAM request failed: HTTP " + code, failure.getMessage());
        }
    }

    @Test
    void boundsDeliveryTimeAndDoesNotRetryTimedOutMessages() {
        responseDelay.set(1000);
        client = new DDRemoteDamCallbackClient(url(), SECRET, Duration.ofMillis(250));
        assertFailureOnce();
    }

    @Test
    void preservesTheExplicitAsyncDeliveryTimeoutAndItsNetworkCause() {
        responseDelay.set(1000);
        client = new DDRemoteDamCallbackClient(url(), SECRET, Duration.ofMillis(250));

        DDRemoteDamException failure = assertAsyncFailureOnce();

        assertEquals("Remote DAM service request failed", failure.getMessage());
        assertInstanceOf(HttpTimeoutException.class, failure.getCause());
    }

    @Test
    void boundsTheFullAsyncExchangeWhenHeadersArriveButTheBodyStalls() throws Exception {
        assertStalledBodyIsCancelled(false);
    }

    @Test
    void cancelsTheUnderlyingBodyReadWhenTheCallerCancelsDelivery() throws Exception {
        assertStalledBodyIsCancelled(true);
    }

    @Test
    void rejectsNullAsyncDeliveryArgumentsBeforeNetworkIo() {
        assertThrows(NullPointerException.class, () -> client.deliverAsync(null, ACCOUNT_ID, SESSION, MESSAGE));
        assertThrows(NullPointerException.class, () -> client.deliverAsync(SUBSCRIPTION_ID, null, SESSION, MESSAGE));
        assertThrows(NullPointerException.class, () -> client.deliverAsync(SUBSCRIPTION_ID, ACCOUNT_ID, null, MESSAGE));
        assertThrows(NullPointerException.class, () -> client.deliverAsync(SUBSCRIPTION_ID, ACCOUNT_ID, SESSION, null));
        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsUntrustedOriginFormsAndInvalidSecretsBeforeNetworkIo() {
        for (String origin : List.of("", "/relative", url() + "/prefix", url() + "?query=1", url() + "#fragment",
            "http://user:password@127.0.0.1", "ftp://127.0.0.1", "http://127.0.0.1:65536")) {
            assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamCallbackClient(origin, SECRET));
        }
        for (String secret : List.of("", "short", " ".repeat(32), "x".repeat(31) + "\n")) {
            assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamCallbackClient(url(), secret));
        }
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamCallbackClient(url(), SECRET, Duration.ZERO));
        assertEquals(0, requestCount.get());
    }

    private void deliver() throws DDRemoteDamException {
        client.deliver(SUBSCRIPTION_ID, ACCOUNT_ID, SESSION, MESSAGE);
    }

    @NotNull
    private CompletionStage<Void> deliverAsync() {
        return client.deliverAsync(SUBSCRIPTION_ID, ACCOUNT_ID, SESSION, MESSAGE);
    }

    private void assertFailureOnce() {
        int before = requestCount.get();
        DDRemoteDamException failure = assertThrows(DDRemoteDamException.class, this::deliver);
        assertFalse(failure.toString().contains(SECRET));
        assertEquals(before + 1, requestCount.get());
    }

    @NotNull
    private DDRemoteDamException assertAsyncFailureOnce() {
        int before = requestCount.get();
        CompletionStage<Void> stage = deliverAsync();
        ExecutionException completion = assertThrows(ExecutionException.class,
            () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
        DDRemoteDamException failure = assertInstanceOf(DDRemoteDamException.class, completion.getCause());
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET));
        assertEquals(before + 1, requestCount.get());
        return failure;
    }

    private void assertStalledBodyIsCancelled(boolean cancelDelivery) throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch resumeBody = new CountDownLatch(1);
        CountDownLatch probeFinished = new CountDownLatch(1);
        AtomicReference<IOException> bodyFailure = new AtomicReference<>();
        server.createContext(DDRemoteDamApi.CALLBACK_GRAPHQL_PATH, exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requestCount.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"data\":{\"deliverRemoteDamMessage\":".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                headersSent.countDown();
                if (!resumeBody.await(5, TimeUnit.SECONDS)) {
                    return;
                }
                byte[] padding = " ".repeat(8192).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 128; i++) {
                    exchange.getResponseBody().write(padding);
                    exchange.getResponseBody().flush();
                    Thread.sleep(10);
                }
            } catch (IOException e) {
                bodyFailure.set(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
                probeFinished.countDown();
            }
        });
        client = new DDRemoteDamCallbackClient(url(), SECRET,
            cancelDelivery ? Duration.ofSeconds(5) : Duration.ofSeconds(1));
        CompletableFuture<Void> delivery = deliverAsync().toCompletableFuture();
        try {
            assertTrue(headersSent.await(3, TimeUnit.SECONDS));
            if (cancelDelivery) {
                assertTrue(delivery.cancel(true));
                assertTrue(delivery.isCancelled());
                assertThrows(CancellationException.class, () -> delivery.get(1, TimeUnit.SECONDS));
            } else {
                ExecutionException completion = assertThrows(ExecutionException.class,
                    () -> delivery.get(3, TimeUnit.SECONDS));
                DDRemoteDamException failure = assertInstanceOf(DDRemoteDamException.class, completion.getCause());
                assertEquals("Remote DAM service request failed", failure.getMessage());
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                assertFalse(trace.toString().contains(SECRET));
            }
            resumeBody.countDown();
            assertTrue(probeFinished.await(5, TimeUnit.SECONDS));
            assertNotNull(bodyFailure.get());
            assertEquals(1, requestCount.get());
            if (cancelDelivery) {
                assertTrue(delivery.isCancelled());
                assertThrows(CancellationException.class, () -> delivery.get(1, TimeUnit.SECONDS));
            }
        } finally {
            resumeBody.countDown();
            delivery.cancel(true);
        }
    }

    @NotNull
    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(@NotNull HttpExchange exchange) throws IOException {
        try {
            request.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst(DDRemoteDamApi.HEADER_SERVICE_TOKEN),
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Cache-Control"),
                JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()));
            requestCount.incrementAndGet();
            requestReceived.countDown();
            CountDownLatch gate = responseGate.get();
            if (gate != null && !gate.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release the response");
            }
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
