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
package com.dbeaver.datadam.messaging.api.client;

import com.dbeaver.datadam.messaging.api.DDMessagingApi;
import com.dbeaver.datadam.messaging.api.exception.DDMessagingException;
import com.dbeaver.datadam.messaging.api.model.DDMessagingConnection;
import com.dbeaver.datadam.messaging.api.model.DDMessagingMessage;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class DDMessagingClientTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("58c487c0-de34-4b45-83bb-705cb733cbcd");
    private static final UUID OTHER_ACCOUNT_ID = UUID.fromString("678d5732-708f-441e-bc59-719b92af1d64");
    private static final String SERVICE_SECRET = "external-dam-service-secret-2026-test";
    private static final String CALLBACK_URL = "https://callback.example.com";
    private static final String CALLBACK_SECRET = "external-dam-callback-secret-2026-test";
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
    private final AtomicReference<CountDownLatch> requestReceived = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> responseGate = new AtomicReference<>();
    private HttpServer server;
    private DDMessagingClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        client = new DDMessagingClient(serviceUrl(), SERVICE_SECRET);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
        assertNull(handlerFailure.get());
    }

    @Test
    void postsConnectionQueriesToTheInternalEndpointWithEitherRootUrlForm() throws DDMessagingException {
        respond(CONNECTIONS_FIELD, new JsonArray());
        for (String suffix : List.of("", "/")) {
            var configured = new DDMessagingClient(serviceUrl() + suffix, SERVICE_SECRET);

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
    void returnsAllAsyncOperationsBeforeDelayedResponsesAndParsesTheirResults() throws Exception {
        JsonArray connections = new JsonArray();
        connections.add(connection("connection-1"));
        respond(CONNECTIONS_FIELD, connections);

        List<DDMessagingConnection> result = awaitDelayedResponse(() -> client.getConnectionsAsync(ACCOUNT_ID));

        assertEquals(List.of(new DDMessagingConnection("DI_desktop", "run-1", "connection-1", "DBeaver", "26.2.4")), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        assertEquals("ExternalDamConnections", posted().get("operationName").getAsString());
        assertEquals(ACCOUNT_ID.toString(), posted().getAsJsonObject("variables").get("accountId").getAsString());

        respond(CONNECTION_ACCOUNT_FIELD, JsonParser.parseString("\"" + ACCOUNT_ID + "\""));
        assertEquals(ACCOUNT_ID, awaitDelayedResponse(() -> client.getConnectionAccountAsync("connection-1")));
        assertEquals("ExternalDamConnectionAccount", posted().get("operationName").getAsString());
        assertEquals("connection-1", posted().getAsJsonObject("variables").get("connectionId").getAsString());

        respond(SEND_FIELD, JsonParser.parseString("false"));
        assertFalse(awaitDelayedResponse(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message())));
        assertEquals("SendExternalDamMessage", posted().get("operationName").getAsString());
        assertEquals(ACCOUNT_ID.toString(), posted().getAsJsonObject("variables").get("accountId").getAsString());
        assertEquals("connection-1", posted().getAsJsonObject("variables").get("connectionId").getAsString());

        UUID subscriptionId = UUID.randomUUID();
        respond("registerExternalDamSubscription", JsonParser.parseString("60"));
        assertEquals(60, awaitDelayedResponse(() -> client.registerSubscriptionAsync("service account", subscriptionId,
            ACCOUNT_ID, null, "projects", CALLBACK_URL, CALLBACK_SECRET)).intValue());
        JsonObject input = posted().getAsJsonObject("variables").getAsJsonObject("input");
        assertEquals("RegisterExternalDamSubscription", posted().get("operationName").getAsString());
        assertEquals("service account", input.get("subscriberId").getAsString());
        assertEquals(subscriptionId.toString(), input.get("subscriptionId").getAsString());
        assertEquals(ACCOUNT_ID.toString(), input.get("accountId").getAsString());
        assertEquals("projects", input.get("topic").getAsString());
        assertEquals(CALLBACK_URL, input.get("callbackUrl").getAsString());
        assertEquals(CALLBACK_SECRET, input.get("callbackSecret").getAsString());
        assertTrue(!input.has("connectionId") || input.get("connectionId").isJsonNull());

        respond("unregisterExternalDamSubscription", JsonParser.parseString("false"));
        assertFalse(awaitDelayedResponse(() -> client.unregisterSubscriptionAsync("service account", subscriptionId)));
        assertEquals("UnregisterExternalDamSubscription", posted().get("operationName").getAsString());
        assertEquals(Set.of("subscriberId", "subscriptionId"), posted().getAsJsonObject("variables").keySet());
        assertEquals("service account", posted().getAsJsonObject("variables").get("subscriberId").getAsString());
        assertEquals(subscriptionId.toString(), posted().getAsJsonObject("variables").get("subscriptionId").getAsString());
        assertEquals(5, requestCount.get());
    }

    @Test
    void sendsAsyncMessagesWithExactJsonNumbersNullsAndUtf8() throws Exception {
        JsonObject payload = JsonParser.parseString("""
            {"integer":9007199254740993,"decimal":1.2345678901234567890123456789,
             "nothing":null,"nested":[null,{"text":"Привет"}]}
            """).getAsJsonObject();
        DDMessagingMessage message = new DDMessagingMessage("topic-\"\\\n", "type-\"\\\n", payload);
        respond(SEND_FIELD, JsonParser.parseString("true"));

        assertTrue(client.sendMessageAsync(ACCOUNT_ID, "connection-\"\\\n", message).toCompletableFuture().get(5, TimeUnit.SECONDS));

        JsonObject variables = posted().getAsJsonObject("variables");
        assertEquals("connection-\"\\\n", variables.get("connectionId").getAsString());
        JsonObject sent = variables.getAsJsonObject("message");
        assertEquals(message.topic(), sent.get("topic").getAsString());
        assertEquals(message.type(), sent.get("type").getAsString());
        assertEquals(payload, sent.getAsJsonObject("payload"));
        assertEquals("9007199254740993", sent.getAsJsonObject("payload").get("integer").getAsString());
        assertEquals("1.2345678901234567890123456789", sent.getAsJsonObject("payload").get("decimal").getAsString());
        assertTrue(sent.getAsJsonObject("payload").get("nothing").isJsonNull());
    }

    @Test
    void resolvesTheConnectionOwnerWithOneAuthenticatedReadAndNoDiscovery() throws DDMessagingException {
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
            assertAsyncRequestFails(() -> client.getConnectionAccountAsync("connection-1"));
        }
        responseJson.set("{\"data\":{}}");
        assertRequestFails(() -> client.getConnectionAccount("connection-1"));
        assertAsyncRequestFails(() -> client.getConnectionAccountAsync("connection-1"));
    }

    @Test
    void rejectsBlankAndOversizedConnectionOwnerLookupIdsBeforeSendingAnyRequest() {
        for (String connectionId : List.of("", " ", "\t\n", "s".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> client.getConnectionAccount(connectionId));
            assertThrows(IllegalArgumentException.class, () -> client.getConnectionAccountAsync(connectionId));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void preservesDistinctConcurrentConnectionsAndReturnsAnImmutableSnapshot() throws DDMessagingException {
        JsonArray connections = new JsonArray();
        connections.add(connection("connection-1"));
        connections.add(connection("connection-2"));
        respond(CONNECTIONS_FIELD, connections);

        List<DDMessagingConnection> result = client.getConnections(ACCOUNT_ID);

        assertEquals(List.of(
            new DDMessagingConnection("DI_desktop", "run-1", "connection-1", "DBeaver", "26.2.4"),
            new DDMessagingConnection("DI_desktop", "run-1", "connection-2", "DBeaver", "26.2.4")
        ), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(result.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> result.set(0, result.get(1)));
        assertThrows(UnsupportedOperationException.class, () -> result.remove(0));

        respond(CONNECTIONS_FIELD, new JsonArray());
        List<DDMessagingConnection> empty = client.getConnections(ACCOUNT_ID);

        assertEquals(List.of(), empty);
        assertThrows(UnsupportedOperationException.class, () -> empty.add(result.get(0)));
        assertEquals(2, result.size());
        assertEquals("connection-1", result.get(0).connectionId());
        assertEquals(2, requestCount.get());
    }

    @Test
    void scopesBothOperationsToTheAccountOnEveryRequest() throws DDMessagingException {
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
    void sendsOpaqueMetadataAsVariablesAndPreservesTheDeepObjectPayload() throws DDMessagingException {
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
        DDMessagingMessage message = new DDMessagingMessage(topic, type, payload);
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
    void returnsTheSendBooleanWithoutAdditionalRequests() throws DDMessagingException {
        for (boolean written : List.of(true, false)) {
            respond(SEND_FIELD, JsonParser.parseString(Boolean.toString(written)));
            int before = requestCount.get();

            assertEquals(written, client.sendMessage(ACCOUNT_ID, "connection-1", message()));

            assertEquals(before + 1, requestCount.get());
            assertEquals("SendExternalDamMessage", posted().get("operationName").getAsString());
        }
    }

    @Test
    void registersDynamicCallbacksAsVariablesAndPreservesStableIdsAndOptionalConnectionScope() throws DDMessagingException {
        UUID subscriptionId = UUID.randomUUID();
        for (String connectionId : List.of("", "socket")) {
            String callbackUrl = connectionId.isEmpty() ? CALLBACK_URL : "http://127.0.0.1:8080/";
            String callbackSecret = connectionId.isEmpty() ? CALLBACK_SECRET : CALLBACK_SECRET + "\"\\";
            respond("registerExternalDamSubscription", JsonParser.parseString("60"));

            assertEquals(60, client.registerSubscription("account", subscriptionId, ACCOUNT_ID,
                connectionId.isEmpty() ? null : connectionId, "projects", callbackUrl, callbackSecret));

            JsonObject body = posted();
            assertEquals(Set.of("operationName", "query", "variables"), body.keySet());
            assertEquals("RegisterExternalDamSubscription", body.get("operationName").getAsString());
            String query = body.get("query").getAsString().replaceAll("\\s+", " ");
            assertTrue(query.contains("$input: ExternalDamSubscriptionInput!"));
            assertTrue(query.contains("registerExternalDamSubscription(input: $input)"));
            assertEquals(Set.of("input"), body.getAsJsonObject("variables").keySet());
            JsonObject input = body.getAsJsonObject("variables").getAsJsonObject("input");
            assertEquals("account", input.get("subscriberId").getAsString());
            assertEquals(subscriptionId.toString(), input.get("subscriptionId").getAsString());
            assertEquals(ACCOUNT_ID.toString(), input.get("accountId").getAsString());
            assertEquals("projects", input.get("topic").getAsString());
            assertEquals(callbackUrl, input.get("callbackUrl").getAsString());
            assertTrue(callbackSecret.equals(input.get("callbackSecret").getAsString()));
            if (connectionId.isEmpty()) {
                assertTrue(!input.has("connectionId") || input.get("connectionId").isJsonNull());
            } else {
                assertEquals(connectionId, input.get("connectionId").getAsString());
            }
            assertEquals(input.has("connectionId") ? 7 : 6, input.size());
            assertFalse(input.has("url"));
            assertFalse(input.has("serviceSecret"));
            assertFalse(query.contains(subscriptionId.toString()));
            assertFalse(query.contains(callbackUrl));
            assertFalse(query.contains(callbackSecret));
        }
        assertEquals(2, requestCount.get());
    }

    @Test
    void registersTheServiceSecretAsTheCallbackSecret() throws DDMessagingException {
        respond("registerExternalDamSubscription", JsonParser.parseString("60"));

        assertEquals(60, client.registerSubscription("account", UUID.randomUUID(), ACCOUNT_ID, null,
            "projects", CALLBACK_URL, SERVICE_SECRET));

        CapturedRequest request = latestRequest.get();
        assertNotNull(request);
        assertEquals(SERVICE_SECRET, request.header(DDMessagingApi.HEADER_SERVICE_TOKEN));
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals(SERVICE_SECRET, body.getAsJsonObject("variables").getAsJsonObject("input")
            .get("callbackSecret").getAsString());
        assertFalse(body.get("query").getAsString().contains(SERVICE_SECRET));
        assertEquals(1, requestCount.get());
    }

    @Test
    void acceptsCallbackRootOriginsAndPrintableSecretBoundaries() throws DDMessagingException {
        UUID subscriptionId = UUID.randomUUID();
        respond("registerExternalDamSubscription", JsonParser.parseString("60"));
        for (String callbackUrl : List.of("http://127.0.0.1:8080", "https://callback.example.com/")) {
            for (String callbackSecret : List.of("!".repeat(32), "~".repeat(512))) {
                assertEquals(60, client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null,
                    "projects", callbackUrl, callbackSecret));

                JsonObject input = posted().getAsJsonObject("variables").getAsJsonObject("input");
                assertEquals(callbackUrl, input.get("callbackUrl").getAsString());
                assertTrue(callbackSecret.equals(input.get("callbackSecret").getAsString()));
            }
        }
        assertEquals(4, requestCount.get());
    }

    @Test
    void preservesSubscriberIdsAcrossRegistrationAndCancellation() throws DDMessagingException {
        for (String subscriberId : List.of("service:account", "service account", "s".repeat(129), "s".repeat(256))) {
            UUID subscriptionId = UUID.randomUUID();
            respond("registerExternalDamSubscription", JsonParser.parseString("60"));
            assertEquals(60, client.registerSubscription(subscriberId, subscriptionId, ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertEquals(subscriberId, posted().getAsJsonObject("variables").getAsJsonObject("input")
                .get("subscriberId").getAsString());

            respond("unregisterExternalDamSubscription", JsonParser.parseString("true"));
            assertTrue(client.unregisterSubscription(subscriberId, subscriptionId));
            assertEquals(subscriberId, posted().getAsJsonObject("variables").get("subscriberId").getAsString());
        }
        assertEquals(8, requestCount.get());
    }

    @Test
    void rejectsMalformedSubscriptionMetadataBeforeSendingAnyRequest() {
        UUID subscriptionId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> client.registerSubscription(null, subscriptionId,
            ACCOUNT_ID, null, "projects", CALLBACK_URL, CALLBACK_SECRET));
        assertThrows(IllegalArgumentException.class, () -> client.unregisterSubscription(null, subscriptionId));
        assertThrows(IllegalArgumentException.class, () -> client.registerSubscriptionAsync(null, subscriptionId,
            ACCOUNT_ID, null, "projects", CALLBACK_URL, CALLBACK_SECRET));
        assertThrows(IllegalArgumentException.class, () -> client.unregisterSubscriptionAsync(null, subscriptionId));
        for (String value : List.of("", " ", "\t\n", "s".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscription(value, subscriptionId,
                ACCOUNT_ID, null, "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertThrows(IllegalArgumentException.class, () -> client.unregisterSubscription(value, subscriptionId));
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscription("account", subscriptionId,
                ACCOUNT_ID, value, "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscription("account", subscriptionId,
                ACCOUNT_ID, null, value, CALLBACK_URL, CALLBACK_SECRET));
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscriptionAsync(value, subscriptionId,
                ACCOUNT_ID, null, "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertThrows(IllegalArgumentException.class, () -> client.unregisterSubscriptionAsync(value, subscriptionId));
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscriptionAsync("account", subscriptionId,
                ACCOUNT_ID, value, "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertThrows(IllegalArgumentException.class, () -> client.registerSubscriptionAsync("account", subscriptionId,
                ACCOUNT_ID, null, value, CALLBACK_URL, CALLBACK_SECRET));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsMalformedCallbackOriginsBeforeSendingAnyRequestWithoutExposingSecrets() {
        UUID subscriptionId = UUID.randomUUID();
        for (String callbackUrl : new String[] {
            null, "", " ", "/relative", "callback.example.com", "//callback.example.com", "http://", "http:///",
            "ftp://callback.example.com", "ws://callback.example.com", "wss://callback.example.com",
            CALLBACK_URL + "/internal/external-dam/graphql", CALLBACK_URL + "/prefix/", CALLBACK_URL + "//",
            CALLBACK_URL + "/%2f", CALLBACK_URL + "?secret=" + CALLBACK_SECRET, CALLBACK_URL + "#fragment",
            "http://user:" + CALLBACK_SECRET + "@127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536",
            "http://127.0.0.1:-1", "http://127.0.0.1:port", "http://[::1", "https://" + "a".repeat(4096)
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null,
                    "projects", callbackUrl, CALLBACK_SECRET));
            assertFalse(failure.toString().contains(CALLBACK_SECRET));
            assertFalse(failure.toString().contains(SERVICE_SECRET));
            IllegalArgumentException asyncFailure = assertThrows(IllegalArgumentException.class,
                () -> client.registerSubscriptionAsync("account", subscriptionId, ACCOUNT_ID, null,
                    "projects", callbackUrl, CALLBACK_SECRET));
            assertFalse(asyncFailure.toString().contains(CALLBACK_SECRET));
            assertFalse(asyncFailure.toString().contains(SERVICE_SECRET));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsInvalidCallbackSecretsBeforeSendingAnyRequest() {
        UUID subscriptionId = UUID.randomUUID();
        for (String callbackSecret : new String[] {
            null, "", "s".repeat(31), "s".repeat(513), " ".repeat(32), "s".repeat(31) + "\n",
            "s".repeat(31) + "\t", "s".repeat(31) + "\r", "s".repeat(31) + "\0",
            "s".repeat(31) + "\u007f", "s".repeat(31) + "é"
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null,
                    "projects", CALLBACK_URL, callbackSecret));
            assertFalse(failure.toString().contains(CALLBACK_SECRET));
            assertFalse(failure.toString().contains(SERVICE_SECRET));
            IllegalArgumentException asyncFailure = assertThrows(IllegalArgumentException.class,
                () -> client.registerSubscriptionAsync("account", subscriptionId, ACCOUNT_ID, null,
                    "projects", CALLBACK_URL, callbackSecret));
            assertFalse(asyncFailure.toString().contains(CALLBACK_SECRET));
            assertFalse(asyncFailure.toString().contains(SERVICE_SECRET));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void unregistersOnlyTheStableSubscriberAndSubscriptionId() throws DDMessagingException {
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
    void acceptsBothSubscriptionLeaseBoundaries() throws Exception {
        for (int lease : List.of(15, 300)) {
            respond("registerExternalDamSubscription", JsonParser.parseString(Integer.toString(lease)));
            assertEquals(lease, client.registerSubscription("account", UUID.randomUUID(), ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertEquals(lease, client.registerSubscriptionAsync("s".repeat(256), UUID.randomUUID(), ACCOUNT_ID, "socket",
                "projects", CALLBACK_URL, CALLBACK_SECRET).toCompletableFuture().get(5, TimeUnit.SECONDS).intValue());
            JsonObject input = posted().getAsJsonObject("variables").getAsJsonObject("input");
            assertEquals("s".repeat(256), input.get("subscriberId").getAsString());
            assertEquals("socket", input.get("connectionId").getAsString());
        }
    }

    @Test
    void rejectsInvalidLeaseValuesAndMalformedSubscriptionEnvelopesWithoutRetrying() {
        UUID subscriptionId = UUID.randomUUID();
        for (String value : List.of("null", "true", "\"60\"", "14", "301", "60.5", "1e100", "{}", "[]")) {
            respond("registerExternalDamSubscription", JsonParser.parseString(value));
            assertRequestFails(() -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertAsyncRequestFails(() -> client.registerSubscriptionAsync("account", subscriptionId, ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
        }
        for (String value : List.of("null", "\"true\"", "60", "{}", "[]")) {
            respond("unregisterExternalDamSubscription", JsonParser.parseString(value));
            assertRequestFails(() -> client.unregisterSubscription("account", subscriptionId));
            assertAsyncRequestFails(() -> client.unregisterSubscriptionAsync("account", subscriptionId));
        }
        for (String body : List.of("{", "null", "{}", "{\"data\":null}",
            "{\"errors\":[{\"message\":\"" + UPSTREAM_MARKER + CALLBACK_SECRET + "\"}]}")) {
            responseJson.set(body);
            assertRequestFails(() -> client.registerSubscription("account", subscriptionId, ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertRequestFails(() -> client.unregisterSubscription("account", subscriptionId));
            assertAsyncRequestFails(() -> client.registerSubscriptionAsync("account", subscriptionId, ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            assertAsyncRequestFails(() -> client.unregisterSubscriptionAsync("account", subscriptionId));
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
            assertAsyncRequestFails(() -> client.getConnectionsAsync(ACCOUNT_ID));
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
            assertAsyncRequestFails(() -> client.getConnectionsAsync(ACCOUNT_ID));
            assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));
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
            assertAsyncRequestFails(() -> client.getConnectionsAsync(ACCOUNT_ID));
            DDMessagingException failure = assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));
            assertEquals("External DAM GraphQL request failed", failure.getMessage());
        }
    }

    @Test
    void acceptsAnEmptyGraphqlErrorsArray() throws Exception {
        JsonObject envelope = successfulEnvelope();
        envelope.add("errors", new JsonArray());
        responseJson.set(envelope.toString());

        assertEquals(List.of(), client.getConnections(ACCOUNT_ID));
        assertTrue(client.sendMessage(ACCOUNT_ID, "connection-1", message()));
        assertEquals(List.of(), client.getConnectionsAsync(ACCOUNT_ID).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(4, requestCount.get());
    }

    @Test
    void rejectsSendResultsThatAreNotJsonBooleans() {
        for (String value : List.of("null", "\"true\"", "\"false\"", "0", "1", "{}", "[]")) {
            respond(SEND_FIELD, JsonParser.parseString(value));

            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
            assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));
        }
    }

    @Test
    void rejectsHttpFailuresWithoutExposingTheResponseOrRetrying() {
        JsonObject envelope = successfulEnvelope();
        envelope.addProperty("message", UPSTREAM_MARKER + SERVICE_SECRET + CALLBACK_SECRET);
        responseJson.set(envelope.toString());
        for (int status : List.of(204, 400, 401, 403, 404, 429, 500, 503)) {
            responseStatus.set(status);

            assertRequestFails(() -> client.getConnections(ACCOUNT_ID));
            assertRequestFails(() -> client.getConnectionAccount("connection-1"));
            assertRequestFails(() -> client.sendMessage(ACCOUNT_ID, "connection-1", message()));
            assertRequestFails(() -> client.registerSubscription("account", UUID.randomUUID(), ACCOUNT_ID, null,
                "projects", CALLBACK_URL, CALLBACK_SECRET));
            DDMessagingException failure = assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));
            assertEquals(status == 204 ? "Invalid External DAM response" : "External DAM request failed: HTTP " + status,
                failure.getMessage());
        }
    }

    @Test
    void neverFollowsRedirectsOrForwardsTheServiceSecret() throws IOException {
        var forwarded = new AtomicInteger();
        var forwardedSecret = new AtomicReference<String>();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> {
            forwarded.incrementAndGet();
            forwardedSecret.set(exchange.getRequestHeaders().getFirst(DDMessagingApi.HEADER_SERVICE_TOKEN));
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
                assertRequestFails(() -> client.registerSubscription("account", UUID.randomUUID(), ACCOUNT_ID, null,
                    "projects", CALLBACK_URL, CALLBACK_SECRET));
                assertAsyncRequestFails(() -> client.getConnectionAccountAsync("connection-1"));
                assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));
                assertAsyncRequestFails(() -> client.registerSubscriptionAsync("account", UUID.randomUUID(), ACCOUNT_ID, null,
                    "projects", CALLBACK_URL, CALLBACK_SECRET));
                assertEquals(0, forwarded.get());
                assertNull(forwardedSecret.get());
            }
        } finally {
            destination.stop(0);
        }
    }

    @Test
    void neverRetriesOrReplaysFailedMutationsAfterRecovery() throws DDMessagingException {
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
    void preservesAsyncNetworkFailureCausesAndAllowsSubsequentRequests() throws Exception {
        responseJson.set(successfulEnvelope().toString());
        truncateResponse.set(true);

        DDMessagingException failure = assertAsyncRequestFails(() -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()));

        assertEquals("External DAM service request failed", failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        truncateResponse.set(false);
        assertTrue(client.sendMessageAsync(ACCOUNT_ID, "connection-1", message()).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(2, requestCount.get());
    }

    @Test
    void reportsAsyncConnectionFailuresThroughTheStageWithTheirCause() {
        server.stop(0);
        server = null;

        ExecutionException completion = assertThrows(ExecutionException.class,
            () -> client.getConnectionsAsync(ACCOUNT_ID).toCompletableFuture().get(5, TimeUnit.SECONDS));

        DDMessagingException failure = assertInstanceOf(DDMessagingException.class, completion.getCause());
        assertEquals("External DAM service request failed", failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
        assertSafeFailure(failure);
        assertEquals(0, requestCount.get());
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
                () -> new DDMessagingClient(url, SERVICE_SECRET), url);
            assertFalse(failure.toString().contains(SERVICE_SECRET));
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void acceptsHttpAndHttpsRootOriginsAndPrintableSecretBoundaries() {
        for (String url : List.of(serviceUrl(), serviceUrl() + "/", "https://127.0.0.1", "https://127.0.0.1:8443/")) {
            for (String secret : List.of("!".repeat(32), "~".repeat(512))) {
                assertDoesNotThrow(() -> new DDMessagingClient(url, secret));
            }
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsInvalidSecretsAndBlankConnectionIdsBeforeSendingAnyRequest() {
        for (String secret : List.of("", "s".repeat(31), "s".repeat(513), " ".repeat(32),
            "s".repeat(31) + "\n", "s".repeat(31) + "\t", "s".repeat(31) + "\u007f", "s".repeat(31) + "é")) {
            assertThrows(IllegalArgumentException.class, () -> new DDMessagingClient(serviceUrl(), secret));
        }
        for (String connectionId : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> client.sendMessage(ACCOUNT_ID, connectionId, message()));
            assertThrows(IllegalArgumentException.class, () -> client.sendMessageAsync(ACCOUNT_ID, connectionId, message()));
        }
        assertThrows(NullPointerException.class, () -> client.getConnectionsAsync(null));
        assertThrows(NullPointerException.class, () -> client.getConnectionAccountAsync(null));
        assertThrows(NullPointerException.class, () -> client.sendMessageAsync(null, "connection-1", message()));
        assertThrows(NullPointerException.class, () -> client.sendMessageAsync(ACCOUNT_ID, "connection-1", null));
        assertEquals(0, requestCount.get());
    }

    @Nullable
    private <T> T awaitDelayedResponse(@NotNull Supplier<CompletionStage<T>> action) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<CompletionStage<T>> stage = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        requestReceived.set(received);
        responseGate.set(release);
        Thread caller = new Thread(() -> {
            try {
                stage.set(action.get());
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                returned.countDown();
            }
        });
        caller.start();
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(returned.await(1, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertNotNull(stage.get());
            assertFalse(stage.get().toCompletableFuture().isDone());
            release.countDown();
            return stage.get().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            caller.join(5000);
            requestReceived.set(null);
            responseGate.set(null);
        }
    }

    private void handleRequest(@NotNull HttpExchange exchange) {
        try {
            Map<String, List<String>> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            latestRequest.set(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(headers),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            requestCount.incrementAndGet();
            CountDownLatch received = requestReceived.get();
            CountDownLatch gate = responseGate.get();
            if (received != null) {
                received.countDown();
            }
            if (gate != null && !gate.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release the response");
            }
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlerFailure.compareAndSet(null, new IOException(e));
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
        assertEquals(DDMessagingApi.GRAPHQL_PATH, request.uri().toString());
        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("no-store", request.header("Cache-Control"));
        assertEquals(SERVICE_SECRET, request.header(DDMessagingApi.HEADER_SERVICE_TOKEN));
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
        assertAsyncRequestFails(() -> client.getConnectionsAsync(ACCOUNT_ID));
    }

    private void assertRequestFails(@NotNull Executable action) {
        int before = requestCount.get();
        DDMessagingException failure = assertThrows(DDMessagingException.class, action);
        assertEquals(before + 1, requestCount.get());
        assertSafeFailure(failure);
    }

    @NotNull
    private DDMessagingException assertAsyncRequestFails(@NotNull Supplier<? extends CompletionStage<?>> action) {
        int before = requestCount.get();
        CompletionStage<?> stage = action.get();
        ExecutionException completion = assertThrows(ExecutionException.class,
            () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
        DDMessagingException failure = assertInstanceOf(DDMessagingException.class, completion.getCause());
        assertEquals(before + 1, requestCount.get());
        assertSafeFailure(failure);
        return failure;
    }

    private static void assertSafeFailure(@NotNull DDMessagingException failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SERVICE_SECRET));
        assertFalse(trace.toString().contains(CALLBACK_SECRET));
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
    private static DDMessagingMessage message() {
        return new DDMessagingMessage("projects", "project.updated", new JsonObject());
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
