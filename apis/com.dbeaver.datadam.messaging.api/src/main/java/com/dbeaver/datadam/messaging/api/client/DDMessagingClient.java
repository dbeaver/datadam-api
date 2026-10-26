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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class DDMessagingClient extends DDMessagingGraphQlClient {
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String CONNECTIONS_FIELD = "externalDamConnections";
    private static final String CONNECTION_ACCOUNT_FIELD = "externalDamConnectionAccount";
    private static final String SEND_FIELD = "sendExternalDamMessage";
    private static final String CONNECTIONS_OPERATION = "ExternalDamConnections";
    private static final String CONNECTION_ACCOUNT_OPERATION = "ExternalDamConnectionAccount";
    private static final String SEND_OPERATION = "SendExternalDamMessage";
    private static final String CONNECTIONS_QUERY = """
        query ExternalDamConnections($accountId: ID!) {
            externalDamConnections(accountId: $accountId) {
                deploymentId applicationRunId connectionId product version
            }
        }
        """;
    private static final String CONNECTION_ACCOUNT_QUERY = """
        query ExternalDamConnectionAccount($connectionId: ID!) {
            externalDamConnectionAccount(connectionId: $connectionId)
        }
        """;
    private static final String SEND_MUTATION = """
        mutation SendExternalDamMessage($accountId: ID!, $connectionId: ID!, $message: ExternalDamMessageInput!) {
            sendExternalDamMessage(accountId: $accountId, connectionId: $connectionId, message: $message)
        }
        """;
    private static final String REGISTER_MUTATION = """
        mutation RegisterExternalDamSubscription($input: ExternalDamSubscriptionInput!) {
            registerExternalDamSubscription(input: $input)
        }
        """;
    private static final String UNREGISTER_MUTATION = """
        mutation UnregisterExternalDamSubscription($subscriberId: String!, $subscriptionId: ID!) {
            unregisterExternalDamSubscription(subscriberId: $subscriberId, subscriptionId: $subscriptionId)
        }
        """;

    public DDMessagingClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        super(serviceUrl, serviceSecret, DDMessagingApi.GRAPHQL_PATH, READ_TIMEOUT_MILLIS);
    }

    @NotNull
    public List<DDMessagingConnection> getConnections(@NotNull UUID accountId) throws DDMessagingException {
        return readConnections(request(CONNECTIONS_OPERATION, CONNECTIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), CONNECTIONS_FIELD));
    }

    @NotNull
    public CompletionStage<List<DDMessagingConnection>> getConnectionsAsync(@NotNull UUID accountId) {
        return requestAsync(CONNECTIONS_OPERATION, CONNECTIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), CONNECTIONS_FIELD, DDMessagingClient::readConnections);
    }

    @NotNull
    public UUID getConnectionAccount(@NotNull String connectionId) throws DDMessagingException {
        return readConnectionAccount(request(CONNECTION_ACCOUNT_OPERATION, CONNECTION_ACCOUNT_QUERY,
            connectionAccountVariables(connectionId), CONNECTION_ACCOUNT_FIELD));
    }

    @NotNull
    public CompletionStage<UUID> getConnectionAccountAsync(@NotNull String connectionId) {
        return requestAsync(CONNECTION_ACCOUNT_OPERATION, CONNECTION_ACCOUNT_QUERY,
            connectionAccountVariables(connectionId), CONNECTION_ACCOUNT_FIELD, DDMessagingClient::readConnectionAccount);
    }

    public boolean sendMessage(@NotNull UUID accountId, @NotNull String connectionId, @NotNull DDMessagingMessage message)
            throws DDMessagingException {
        return readBoolean(request(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, connectionId, message), SEND_FIELD));
    }

    @NotNull
    public CompletionStage<Boolean> sendMessageAsync(
        @NotNull UUID accountId,
        @NotNull String connectionId,
        @NotNull DDMessagingMessage message
    ) {
        return requestAsync(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, connectionId, message), SEND_FIELD,
            DDMessagingGraphQlClient::readBoolean);
    }

    public int registerSubscription(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String connectionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) throws DDMessagingException {
        return readLease(request("RegisterExternalDamSubscription", REGISTER_MUTATION,
            subscriptionVariables(subscriberId, subscriptionId, accountId, connectionId, topic, callbackUrl, callbackSecret),
            "registerExternalDamSubscription"));
    }

    @NotNull
    public CompletionStage<Integer> registerSubscriptionAsync(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String connectionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) {
        return requestAsync("RegisterExternalDamSubscription", REGISTER_MUTATION,
            subscriptionVariables(subscriberId, subscriptionId, accountId, connectionId, topic, callbackUrl, callbackSecret),
            "registerExternalDamSubscription", DDMessagingClient::readLease);
    }

    public boolean unregisterSubscription(@NotNull String subscriberId, @NotNull UUID subscriptionId) throws DDMessagingException {
        return readBoolean(request("UnregisterExternalDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterExternalDamSubscription"));
    }

    @NotNull
    public CompletionStage<Boolean> unregisterSubscriptionAsync(@NotNull String subscriberId, @NotNull UUID subscriptionId) {
        return requestAsync("UnregisterExternalDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterExternalDamSubscription",
            DDMessagingGraphQlClient::readBoolean);
    }

    @NotNull
    private static List<DDMessagingConnection> readConnections(@NotNull JsonElement result) throws DDMessagingException {
        if (!result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDMessagingConnection> connections = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            JsonObject connection = readObject(value);
            connections.add(new DDMessagingConnection(
                readString(connection, "deploymentId"), readString(connection, "applicationRunId"),
                readString(connection, "connectionId"), readString(connection, "product"), readString(connection, "version")
            ));
        }
        return List.copyOf(connections);
    }

    @NotNull
    private static UUID readConnectionAccount(@NotNull JsonElement result) throws DDMessagingException {
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isString()) {
            throw invalidResponse();
        }
        UUID accountId;
        try {
            accountId = UUID.fromString(result.getAsString());
        } catch (IllegalArgumentException e) {
            throw invalidResponse();
        }
        if (!accountId.toString().equalsIgnoreCase(result.getAsString())) {
            throw invalidResponse();
        }
        return accountId;
    }

    private static int readLease(@NotNull JsonElement result) throws DDMessagingException {
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse();
        }
        try {
            int leaseSeconds = result.getAsBigDecimal().intValueExact();
            if (leaseSeconds >= DDMessagingApi.MIN_SUBSCRIPTION_LEASE_SECONDS
                && leaseSeconds <= DDMessagingApi.MAX_SUBSCRIPTION_LEASE_SECONDS) {
                return leaseSeconds;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }

    @NotNull
    private static Map<String, ?> connectionAccountVariables(@NotNull String connectionId) {
        if (connectionId.isBlank() || connectionId.length() > 256) {
            throw new IllegalArgumentException("Invalid connection ID");
        }
        return Map.of("connectionId", connectionId);
    }

    @NotNull
    private static Map<String, ?> messageVariables(
        @NotNull UUID accountId,
        @NotNull String connectionId,
        @NotNull DDMessagingMessage message
    ) {
        if (connectionId.isBlank()) {
            throw new IllegalArgumentException("A connection ID is required");
        }
        return Map.of(
            "accountId", Objects.requireNonNull(accountId).toString(), "connectionId", connectionId,
            "message", Objects.requireNonNull(message)
        );
    }

    @NotNull
    private static Map<String, ?> subscriptionVariables(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String connectionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) {
        requireSubscriberId(subscriberId);
        requireText(topic);
        if (connectionId != null) {
            requireText(connectionId);
        }
        if (!DDMessagingApi.isValidServiceUrl(callbackUrl)) {
            throw new IllegalArgumentException("Invalid External DAM callback URL");
        }
        if (!DDMessagingApi.isValidServiceSecret(callbackSecret)) {
            throw new IllegalArgumentException("Invalid External DAM callback secret");
        }
        Map<String, Object> input = new HashMap<>();
        input.put("subscriberId", subscriberId);
        input.put("subscriptionId", Objects.requireNonNull(subscriptionId).toString());
        input.put("accountId", Objects.requireNonNull(accountId).toString());
        input.put("connectionId", connectionId);
        input.put("topic", topic);
        input.put("callbackUrl", callbackUrl);
        input.put("callbackSecret", callbackSecret);
        return Map.of("input", input);
    }

    @NotNull
    private static Map<String, ?> unsubscriptionVariables(@NotNull String subscriberId, @NotNull UUID subscriptionId) {
        requireSubscriberId(subscriberId);
        return Map.of("subscriberId", subscriberId, "subscriptionId", Objects.requireNonNull(subscriptionId).toString());
    }

    @NotNull
    private static String readString(@NotNull JsonObject object, @NotNull String field) throws DDMessagingException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalidResponse();
        }
        return value.getAsString();
    }

    private static void requireSubscriberId(@Nullable String subscriberId) {
        if (!DDMessagingApi.isValidSubscriberId(subscriberId)) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }

    private static void requireText(@NotNull String value) {
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }
}
