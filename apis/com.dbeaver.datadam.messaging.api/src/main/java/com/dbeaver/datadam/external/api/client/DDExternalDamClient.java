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

public final class DDExternalDamClient extends DDExternalDamGraphQlClient {
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

    public DDExternalDamClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        super(serviceUrl, serviceSecret, DDExternalDamApi.GRAPHQL_PATH, READ_TIMEOUT_MILLIS);
    }

    @NotNull
    public List<DDExternalDamConnection> getConnections(@NotNull UUID accountId) throws DDExternalDamException {
        return readConnections(request(CONNECTIONS_OPERATION, CONNECTIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), CONNECTIONS_FIELD));
    }

    @NotNull
    public CompletionStage<List<DDExternalDamConnection>> getConnectionsAsync(@NotNull UUID accountId) {
        return requestAsync(CONNECTIONS_OPERATION, CONNECTIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), CONNECTIONS_FIELD, DDExternalDamClient::readConnections);
    }

    @NotNull
    public UUID getConnectionAccount(@NotNull String connectionId) throws DDExternalDamException {
        return readConnectionAccount(request(CONNECTION_ACCOUNT_OPERATION, CONNECTION_ACCOUNT_QUERY,
            connectionAccountVariables(connectionId), CONNECTION_ACCOUNT_FIELD));
    }

    @NotNull
    public CompletionStage<UUID> getConnectionAccountAsync(@NotNull String connectionId) {
        return requestAsync(CONNECTION_ACCOUNT_OPERATION, CONNECTION_ACCOUNT_QUERY,
            connectionAccountVariables(connectionId), CONNECTION_ACCOUNT_FIELD, DDExternalDamClient::readConnectionAccount);
    }

    public boolean sendMessage(@NotNull UUID accountId, @NotNull String connectionId, @NotNull DDMessage message)
            throws DDExternalDamException {
        return readBoolean(request(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, connectionId, message), SEND_FIELD));
    }

    @NotNull
    public CompletionStage<Boolean> sendMessageAsync(
        @NotNull UUID accountId,
        @NotNull String connectionId,
        @NotNull DDMessage message
    ) {
        return requestAsync(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, connectionId, message), SEND_FIELD,
            DDExternalDamGraphQlClient::readBoolean);
    }

    public int registerSubscription(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String connectionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) throws DDExternalDamException {
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
            "registerExternalDamSubscription", DDExternalDamClient::readLease);
    }

    public boolean unregisterSubscription(@NotNull String subscriberId, @NotNull UUID subscriptionId) throws DDExternalDamException {
        return readBoolean(request("UnregisterExternalDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterExternalDamSubscription"));
    }

    @NotNull
    public CompletionStage<Boolean> unregisterSubscriptionAsync(@NotNull String subscriberId, @NotNull UUID subscriptionId) {
        return requestAsync("UnregisterExternalDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterExternalDamSubscription", DDExternalDamGraphQlClient::readBoolean);
    }

    @NotNull
    private static List<DDExternalDamConnection> readConnections(@NotNull JsonElement result) throws DDExternalDamException {
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

    @NotNull
    private static UUID readConnectionAccount(@NotNull JsonElement result) throws DDExternalDamException {
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

    private static int readLease(@NotNull JsonElement result) throws DDExternalDamException {
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse();
        }
        try {
            int leaseSeconds = result.getAsBigDecimal().intValueExact();
            if (leaseSeconds >= DDExternalDamApi.MIN_SUBSCRIPTION_LEASE_SECONDS
                && leaseSeconds <= DDExternalDamApi.MAX_SUBSCRIPTION_LEASE_SECONDS) {
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
    private static Map<String, ?> messageVariables(@NotNull UUID accountId, @NotNull String connectionId, @NotNull DDMessage message) {
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
        if (!DDExternalDamApi.isValidServiceUrl(callbackUrl)) {
            throw new IllegalArgumentException("Invalid External DAM callback URL");
        }
        if (!DDExternalDamApi.isValidServiceSecret(callbackSecret)) {
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
    private static String readString(@NotNull JsonObject object, @NotNull String field) throws DDExternalDamException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalidResponse();
        }
        return value.getAsString();
    }

    private static void requireSubscriberId(@Nullable String subscriberId) {
        if (!DDExternalDamApi.isValidSubscriberId(subscriberId)) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }

    private static void requireText(@NotNull String value) {
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }
}
