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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class DDExternalDamClientTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("58c487c0-de34-4b45-83bb-705cb733cbcd");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("678d5732-708f-441e-bc59-719b92af1d64");
    private static final String SERVICE_SECRET = "external-dam-service-secret-2026-test";
    private static final String UPSTREAM_MARKER = "private-upstream-response-marker";
    private static final String CONNECTIONS_FIELD = "externalDamConnections";
    private static final String CONNECTION_ACCOUNT_FIELD = "externalDamConnectionAccount";
    private static final String SEND_FIELD = "sendExternalDamMessage";
    private final AtomicReference<String> responseJson = new AtomicReference<>("{}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> redirect = new AtomicReference<>();
    private final AtomicBoolean truncateResponse = new AtomicBoolean();
    private final AtomicReference<CapturedRequest> latestRequest = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<IOException> handlerFailure = new AtomicReference<>();
    private HttpServer server;
    private DDExternalDamClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        client = new DDExternalDamClient(serviceUrl(), SERVICE_SECRET);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        assertNull(handlerFailure.get());
    }

    @Test
    void postsConnectionQueriesToTheInternalEndpointWithEitherRootUrlForm() throws DDExternalDamException {
        respond(CONNECTIONS_FIELD, new JsonArray());
        for (String suffix : List.of("", "/")) {
            var configured = new DDExternalDamClient(serviceUrl() + suffix, SERVICE_SECRET);

            assertEquals(List.of(), configured.getConnections(ACCOUNT_ID));

            JsonObject body = posted();
            assertEquals(Set.of("operationName", "query", "variables"), body.keySet());
            assertEquals("ExternalDamConnections", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("query ExternalDamConnections($accountId: ID!)"));
            assertTrue(query.contains("externalDamConnections(accountId: $accountId)"));
            for (String field : List.of("deploymentId", "applicationRunId", "connectionId", "product", "version")) {
                assertTrue(query.contains(field), field);
            }
            assertEquals(Set.of("accountId"), body.getAsJsonObject("variables").keySet());
            assertEquals(ACCOUNT_ID.toString(), body.getAsJsonObject("variables").get("accountId").getAsString());
            assertFalse(query.contains(ACCOUNT_ID.toString()));
        }
        assertEquals(2, requestCount.get());
    }

    @Test
    void resolvesTheConnectionOwnerWithOneAuthenticatedReadAndNoDiscovery() throws DDExternalDamException {
        String connectionId = "connection-\"\\\n) { injectedConnection }";
        for (UUID accountId : List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)) {
            respond(CONNECTION_ACCOUNT_FIELD, JsonParser.parseString("\"" + accountId + "\""));

            assertEquals(accountId, client.getConnectionAccount(connectionId));

            JsonObject body = posted();
            assertEquals("ExternalDamConnectionAccount", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("query ExternalDamConnectionAccount($connectionId: ID!)"));
            assertTrue(query.contains("externalDamConnectionAccount(connectionId: $connectionId)"));
            assertEquals(Set.of("connectionId"), body.getAsJsonObject("variables").keySet());
            assertEquals(connectionId, body.getAsJsonObject("variables").get("connectionId").getAsString());
            assertFalse(query.contains("injectedConnection"));
        }
        assertEquals(2, requestCount.get());
    }

    @Test
    void rejectsUnavailableNonStringAndMalformedConnectionOwnersWithoutRetrying() {
        for (String value : List.of("null", "true", "false", "42", "{}", "[]", "\"\"", "\" \\t\"",
            "\"not-a-uuid\"", "\"1-1-1-1-1\"", "\"" + ACCOUNT_ID + " \"", "\"" + UPSTREAM_MARKER + SERVICE_SECRET + "\"")) {
            respond(CONNECTION_ACCOUNT_FIELD, JsonParser.parseString(value));

            assertRequestFails(() -> client.getConnectionAccount("connection-1"));
        }
        responseJson.set("{\"data\":{}}");
        assertRequestFails(() -> client.getConnectionAccount("connection-1"));
    }

    @Test
    void rejectsBlankAndOversizedConnectionOwnerLookupIdsBeforeSendingAnyRequest() {
        for (String connectionId : List.of("", " ", "\t\n", "s".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> client.getConnectionAccount(connectionId));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void preservesDistinctConcurrentConnectionsAndReturnsAnImmutableSnapshot() throws DDExternalDamException {
        JsonArray connections = new JsonArray();
        connections.add(connection("connection-1"));
        connections.add(connection("connection-2"));
        respond(CONNECTIONS_FIELD, connections);

        List<DDExternalDamConnection> result = client.getConnections(ACCOUNT_ID);

        assertEquals(List.of(
            new DDExternalDamConnection("DI_desktop", "run-1", "connection-1", "DBeaver", "26.2.4"),
            new DDExternalDamConnection("DI_desktop", "run-1", "connection-2", "DBeaver", "26.2.4")
        ), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> result.set(0, result.get(1)));
        assertThrows(UnsupportedOperationException.class, () -> result.remove(0));

        respond(CONNECTIONS_FIELD, new JsonArray());
        List<DDExternalDamConnection> empty = client.getConnections(ACCOUNT_ID);

        assertEquals(List.of(), empty);
        assertThrows(UnsupportedOperationException.class, () -> empty.add(result.get(0)));
        assertEquals(2, result.size());
        assertEquals("connection-1", result.get(0).connectionId());
        assertEquals(2, requestCount.get());
    }

    @Test
    void scopesBothOperationsToTheAccountOnEveryRequest() throws DDExternalDamException {
        String connectionsQuery = null;
        String sendQuery = null;
        for (UUID accountId : List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID)) {
            respond(CONNECTIONS_FIELD, new JsonArray());
            assertEquals(List.of(), client.getConnections(accountId));
            JsonObject connectionsBody = posted();
            assertEquals(accountId.toString(), connectionsBody.getAsJsonObject("variables").get("accountId").getAsString());
            String currentConnectionsQuery = connectionsBody.get("query").getAsString();
            if (connectionsQuery != null) {
                assertEquals(connectionsQuery, currentConnectionsQuery);
            }
            connectionsQuery = currentConnectionsQuery;
            assertFalse(connectionsQuery.contains(accountId.toString()));

            respond(SEND_FIELD, JsonParser.parseString("true"));
            assertTrue(client.sendMessage(accountId, "connection-1", message()));
            JsonObject sendBody = posted();
            assertEquals(accountId.toString(), sendBody.getAsJsonObject("variables").get("accountId").getAsString());
            assertEquals("connection-1", sendBody.getAsJsonObject("variables").get("connectionId").getAsString());
            String currentSendQuery = sendBody.get("query").getAsString();
            if (sendQuery != null) {
                assertEquals(sendQuery, currentSendQuery);
            }
            sendQuery = currentSendQuery;
            assertFalse(sendQuery.contains(accountId.toString()));
        }
        assertEquals(4, requestCount.get());
    }

    @Test
    void sendsOpaqueMetadataAsVariablesAndPreservesTheDeepObjectPayload() throws DDExternalDamException {
        JsonObject nested = new JsonObject();
        nested.addProperty("name", "quoted \"value\"");
        nested.addProperty("path", "C:\\temp\\file");
        JsonArray items = new JsonArray();
        items.add(nested);
        items.add(true);
        items.add(42);
        items.add(JsonNull.INSTANCE);
        JsonObject payload = new JsonObject();
        payload.add("items", items);
        payload.addProperty("text", "Привет");
        String topic = "topic-\"\\\n{ injectedTopic }";
        String type = "type-\"\\\n{ injectedType }";
        DDMessage message = new DDMessage(topic, type, payload);
        respond(SEND_FIELD, JsonParser.parseString("true"));

        String connectionId = "connection-\"\\\n) { injectedConnection }";
        assertTrue(client.sendMessage(ACCOUNT_ID, connectionId, message));

        JsonObject body = posted();
        assertEquals(Set.of("operationName", "query", "variables"), body.keySet());
        assertEquals("SendExternalDamMessage", body.get("operationName").getAsString());
        JsonObject variables = body.getAsJsonObject("variables");
        assertEquals(Set.of("accountId", "connectionId", "message"), variables.keySet());
        assertEquals(ACCOUNT_ID.toString(), variables.get("accountId").getAsString());
        assertEquals(connectionId, variables.get("connectionId").getAsString());
        JsonObject input = variables.getAsJsonObject("message");
        assertEquals(Set.of("topic", "type", "payload"), input.keySet());
        assertEquals(topic, input.get("topic").getAsString());
        assertEquals(type, input.get("type").getAsString());
        assertEquals(payload, input.get("payload"));
        assertTrue(input.get("payload").isJsonObject());
        String query = body.get("query").getAsString().replaceAll("\\s+", " ");
        assertTrue(query.contains("mutation SendExternalDamMessage("));
        assertTrue(query.contains("$accountId: ID!"));
        assertTrue(query.contains("$connectionId: ID!"));
        assertTrue(query.contains("$message: ExternalDamMessageInput!"));
        assertTrue(query.contains("sendExternalDamMessage(accountId: $accountId, connectionId: $connectionId, message: $message)"));
        for (String value : List.of(ACCOUNT_ID.toString(), connectionId, topic, type, "injectedConnection", "injectedTopic",
            "injectedType", "Привет", "C:\\temp\\file")) {
            assertFalse(query.contains(value), value);
        }
        assertEquals(1, requestCount.get());
    }

    @Test
    void returnsTheSendBooleanWithoutAdditionalRequests() throws DDExternalDamException {
        for (boolean written : List.of(true, false)) {
            respond(SEND_FIELD, JsonParser.parseString(Boolean.toString(written)));
            int before = requestCount.get();

            assertEquals(written, client.sendMessage(ACCOUNT_ID, "connection-1", message()));

            assertEquals(before + 1, requestCount.get());
            assertEquals("SendExternalDamMessage", posted().get("operationName").getAsString());
        }
    }

    @Test
    void registersStableSubscriptionIdsAsVariablesAndPreservesOptionalConnectionScope() throws DDExternalDamException {
        UUID subscriptionId = UUID.randomUUID();
        for (String connectionId : List.of("", "socket")) {
            respond("registerExternalDamSubscription", JsonParser.parseString("60"));

            assertEquals(60, client.registerSubscription("account", subscriptionId, ACCOUNT_ID,
                connectionId.isEmpty() ? null : connectionId, "projects"));

            JsonObject body = posted();
            assertEquals("RegisterExternalDamSubscription", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("$input: ExternalDamSubscriptionInput!"));
            assertTrue(query.contains("registerExternalDamSubscription(input: $input)"));
            JsonObject input = body.getAsJsonObject("variables").getAsJsonObject("input");
            assertEquals("account", input.get("subscriberId").getAsString());
            assertEquals(subscriptionId.toString(), input.get("subscriptionId").getAsString());
            assertEquals(ACCOUNT_ID.toString(), input.get("accountId").getAsString());
            assertEquals("projects", input.get("topic").getAsString());
            if (connectionId.isEmpty()) {
                assertTrue(!input.has("connectionId") || input.get("connectionId").isJsonNull());
            } else {
                assertEquals(connectionId, input.get("connectionId").getAsString());
            }
            assertFalse(input.has("url"));
            assertFalse(input.has("serviceSecret"));
            assertFalse(query.contains(subscriptionId.toString()));
        }
    }

    @Test
    void unregistersOnlyTheStableSubscriberAndSubscriptionId() throws DDExternalDamException {
        UUID subscriptionId = UUID.randomUUID();
        for (boolean removed : List.of(true, false)) {
            respond("unregisterExternalDamSubscription", JsonParser.parseString(Boolean.toString(removed)));

            assertEquals(removed, client.unregisterSubscription("storage", subscriptionId));

            JsonObject body = posted();
            assertEquals("UnregisterExternalDamSubscription", body.get("operationName").getAsString());
            JsonObject variables = body.getAsJsonObject("variables");
            assertEquals(Set.of("subscriberId", "subscriptionId"), variables.keySet());
            assertEquals("storage", variables.get("subscriberId").getAsString());
            assertEquals(subscriptionId.toString(), variables.get("subscriptionId").getAsString());
        }
    }

    @Test
    void rejectsInvalidLeaseValuesAndMalformedSubscriptionEnvelopesWithoutRetrying() {
        UUID subscriptionId = UUID.randomUUID();
        for (String value : List.of("null", "true", "\"60\"", "14", "301", "60.5", "1e100", "{}", "[]")) {
            respond("registerExternalDamSubscription", JsonParser.parseString(value));
            assertRequestFails(() -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null, "projects"));
        }
        for (String value : List.of("null", "\"true\"", "60", "{}", "[]")) {
            respond("unregisterExternalDamSubscription", JsonParser.parseString(value));
            assertRequestFails(() -> client.unregisterSubscription("account", subscriptionId));
        }
        for (String body : List.of("{", "null", "{}", "{\"data\":null}", "{\"errors\":[{\"message\":\"private\"}]}")) {
            responseJson.set(body);
            assertRequestFails(() -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null, "projects"));
            assertRequestFails(() -> client.unregisterSubscription("account", subscriptionId));
        }
    }

    @Test
    void rejectsTheWholeListWhenAnyRequiredConnectionFieldIsInvalid() {
        for (String field : List.of("deploymentId", "applicationRunId", "connectionId", "product", "version")) {
            JsonObject missing = connection("connection-2");
            missing.remove(field);
            assertInvalidConnection(missing);
            for (String value : List.of("null", "true", "42", "[]", "{}", "\"\"", "\" \\t\\n\"")) {
                JsonObject invalid = connection("connection-2");
                invalid.add(field, JsonParser.parseString(value));
                assertInvalidConnection(invalid);
            }
        }
    }

    @Test
    void rejectsNonArrayConnectionResultsAndNonObjectEntries() {
        for (String value : List.of(
            "null", "true", "42", "\"connections\"", "{}", "[null]", "[true]", "[42]", "[\"connection\"]", "[[]]"
        )) {
            respond(CONNECTIONS_FIELD, JsonParser.parseString(value));

            assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
        }
    }

    @Test
    void rejectsMalformedJsonRootsAndMissingDataForBothOperations() {
        for (String body : List.of(
            "", "{", "not-json", "null", "[]", "true", "42", "\"root\"", "{}",
            "{\"data\":null}", "{\"data\":[]}", "{\"data\":true}", "{\"data\":{}}",
            "{\"data\":{\"externalDamConnections\":null,\"sendExternalDamMessage\":null}}",
            "{\"" + UPSTREAM_MARKER + SERVICE_SECRET + "\":["
        )) {
            responseJson.set(body);

            assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        }
    }

    @Test
    void rejectsMalformedErrorsAndGraphqlErrorsEvenAlongsideUsablePartialData() {
        JsonObject upstreamError = new JsonObject();
        upstreamError.addProperty("message", UPSTREAM_MARKER + SERVICE_SECRET);
        JsonArray errors = new JsonArray();
        errors.add(upstreamError);
        for (JsonElement value : List.of(
            JsonNull.INSTANCE, new JsonObject(), JsonParser.parseString("true"), JsonParser.parseString("42"),
            JsonParser.parseString("\"" + UPSTREAM_MARKER + SERVICE_SECRET + "\""), JsonParser.parseString("[null]"), errors
        )) {
            JsonObject envelope = successfulEnvelope();
            envelope.add("errors", value);
            responseJson.set(envelope.toString());

            assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        }
    }

    @Test
    void acceptsAnEmptyGraphqlErrorsArray() throws DDExternalDamException {
        JsonObject envelope = successfulEnvelope();
        envelope.add("errors", new JsonArray());
        responseJson.set(envelope.toString());

        assertEquals(List.of(), client.getConnections(ACCOUNT_ID));
        assertTrue(client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        assertEquals(2, requestCount.get());
    }

    @Test
    void rejectsSendResultsThatAreNotJsonBooleans() {
        for (String value : List.of("null", "\"true\"", "\"false\"", "0", "1", "{}", "[]")) {
            respond(SEND_FIELD, JsonParser.parseString(value));

            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        }
    }

    @Test
    void rejectsHttpFailuresWithoutExposingTheResponseOrRetrying() {
        JsonObject envelope = successfulEnvelope();
        envelope.addProperty("message", UPSTREAM_MARKER + SERVICE_SECRET);
        responseJson.set(envelope.toString());
        for (int status : List.of(204, 400, 401, 403, 404, 429, 500, 503)) {
            responseStatus.set(status);

            assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
            assertRequestFails(() -> client.getConnectionAccount("connection-1"));
            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        }
    }

    @Test
    void neverFollowsRedirectsOrForwardsTheServiceSecret() throws IOException {
        var forwarded = new AtomicInteger();
        var forwardedSecret = new AtomicReference<String>();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> {
            forwarded.incrementAndGet();
            forwardedSecret.set(exchange.getRequestHeaders().getFirst(DDExternalDamApi.HEADER_SERVICE_TOKEN));
            try {
                exchange.sendResponseHeaders(200, -1);
            } catch (IOException e) {
                handlerFailure.compareAndSet(null, e);
            } finally {
                exchange.close();
            }
        });
        destination.start();
        try {
            redirect.set("http://127.0.0.1:" + destination.getAddress().getPort() + "/destination");
            responseJson.set(successfulEnvelope().toString());
            for (int status : List.of(301, 302, 303, 307, 308)) {
                responseStatus.set(status);

                assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
                assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
                assertEquals(0, forwarded.get());
                assertNull(forwardedSecret.get());
            }
        } finally {
            destination.stop(0);
        }
    }

    @Test
    void neverRetriesOrReplaysFailedMutationsAfterRecovery() throws DDExternalDamException {
        responseJson.set("{\"errors\":[{\"message\":\"" + UPSTREAM_MARKER + SERVICE_SECRET + "\"}]}");
        assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));

        responseJson.set(successfulEnvelope().toString());
        responseStatus.set(503);
        assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));

        responseStatus.set(200);
        truncateResponse.set(true);
        assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));

        truncateResponse.set(false);
        assertTrue(client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        assertEquals(4, requestCount.get());
    }

    @Test
    void rejectsInvalidServiceOriginsBeforeSendingAnyRequest() {
        for (String url : List.of(
            "", "/relative", "127.0.0.1", "//127.0.0.1", "http:///", "http://",
            "ftp://127.0.0.1", "ws://127.0.0.1", "wss://127.0.0.1",
            serviceUrl() + "/internal/graphql", serviceUrl() + "/ws", serviceUrl() + "/prefix/", serviceUrl() + "//",
            serviceUrl() + "/%2f", serviceUrl() + "?secret=" + SERVICE_SECRET, serviceUrl() + "#fragment",
            "http://user:" + SERVICE_SECRET + "@127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536"
        )) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new DDExternalDamClient(url, SERVICE_SECRET), url);
            assertFalse(failure.toString().contains(SERVICE_SECRET));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void acceptsHttpAndHttpsRootOriginsAndPrintableSecretBoundaries() {
        for (String url : List.of(serviceUrl(), serviceUrl() + "/", "https://127.0.0.1", "https://127.0.0.1:8443/")) {
            for (String secret : List.of("!".repeat(32), "~".repeat(512))) {
                assertDoesNotThrow(() -> new DDExternalDamClient(url, secret));
            }
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsInvalidSecretsAndBlankConnectionIdsBeforeSendingAnyRequest() {
        for (String secret : List.of("", "s".repeat(31), "s".repeat(513), " ".repeat(32),
            "s".repeat(31) + "\n", "s".repeat(31) + "\t", "s".repeat(31) + "\u007f", "s".repeat(31) + "é")) {
            assertThrows(IllegalArgumentException.class, () -> new DDExternalDamClient(serviceUrl(), secret));
        }
        for (String connectionId : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> client.sendMessage(ACCOUNT_ID, connectionId, message()));
        }
        assertEquals(0, requestCount.get());
    }

    private void handleRequest(@NotNull HttpExchange exchange) {
        try {
            Map<String, List<String>> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            latestRequest.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(headers),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            requestCount.incrementAndGet();
            String location = redirect.get();
            if (location != null) {
                exchange.getResponseHeaders().set("Location", location);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            int status = responseStatus.get();
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] bytes = responseJson.get().getBytes(StandardCharsets.UTF_8);
                boolean truncated = truncateResponse.get();
                if (truncated) {
                    exchange.getResponseHeaders().set("Connection", "close");
                }
                exchange.sendResponseHeaders(status, bytes.length + (truncated ? 16 : 0));
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException e) {
            handlerFailure.compareAndSet(null, e);
        } finally {
            exchange.close();
        }
    }

    @NotNull
    private String serviceUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @NotNull
    private JsonObject posted() {
        CapturedRequest request = latestRequest.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals(DDExternalDamApi.GRAPHQL_PATH, request.uri().toString());
        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("no-store", request.header("Cache-Control"));
        assertEquals(SERVICE_SECRET, request.header(DDExternalDamApi.HEADER_SERVICE_TOKEN));
        assertNull(request.header("Authorization"));
        assertNull(request.header("Cookie"));
        assertFalse(request.body().contains(SERVICE_SECRET));
        assertFalse(request.uri().toString().contains(SERVICE_SECRET));
        return JsonParser.parseString(request.body()).getAsJsonObject();
    }

    private void respond(@NotNull String field, @NotNull JsonElement value) {
        JsonObject data = new JsonObject();
        data.add(field, value);
        JsonObject envelope = new JsonObject();
        envelope.add("data", data);
        responseJson.set(envelope.toString());
    }

    private void assertInvalidConnection(@NotNull JsonObject invalid) {
        JsonArray connections = new JsonArray();
        connections.add(connection("connection-1"));
        connections.add(invalid);
        respond(CONNECTIONS_FIELD, connections);
        assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
    }

    private void assertRequestFails(@NotNull Executable action) {
        int before = requestCount.get();
        DDExternalDamException failure = assertThrows(DDExternalDamException.class, action);
        assertEquals(before + 1, requestCount.get());
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SERVICE_SECRET));
        assertFalse(trace.toString().contains(UPSTREAM_MARKER));
    }

    @NotNull
    private static JsonObject connection(@NotNull String connectionId) {
        JsonObject connection = new JsonObject();
        connection.addProperty("deploymentId", "DI_desktop");
        connection.addProperty("applicationRunId", "run-1");
        connection.addProperty("connectionId", connectionId);
        connection.addProperty("product", "DBeaver");
        connection.addProperty("version", "26.2.4");
        return connection;
    }

    @NotNull
    private static JsonObject successfulEnvelope() {
        JsonObject data = new JsonObject();
        data.add(CONNECTIONS_FIELD, new JsonArray());
        data.addProperty(SEND_FIELD, true);
        JsonObject envelope = new JsonObject();
        envelope.add("data", data);
        return envelope;
    }

    @NotNull
    private static DDMessage message() {
        return new DDMessage("projects", "project.updated", new JsonObject());
    }

    private record CapturedRequest(
        @NotNull String method,
        @NotNull URI uri,
        @NotNull Map<String, List<String>> headers,
        @NotNull String body
    ) {
        @Nullable
        private String header(@NotNull String name) {
            return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(entry -> entry.getValue().get(0))
                .findFirst()
                .orElse(null);
        }
    }
}
