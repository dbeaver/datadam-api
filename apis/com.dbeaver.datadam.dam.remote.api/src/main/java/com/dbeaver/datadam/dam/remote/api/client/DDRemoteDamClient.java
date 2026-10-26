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
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSession;
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

public final class DDRemoteDamClient extends DDRemoteDamGraphQlClient {
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String SESSIONS_FIELD = "remoteDamSessions";
    private static final String SESSION_ACCOUNT_FIELD = "remoteDamSessionAccount";
    private static final String SEND_FIELD = "sendRemoteDamMessage";
    private static final String SESSIONS_OPERATION = "RemoteDamSessions";
    private static final String SESSION_ACCOUNT_OPERATION = "RemoteDamSessionAccount";
    private static final String SEND_OPERATION = "SendRemoteDamMessage";
    private static final String SESSIONS_QUERY = """
        query RemoteDamSessions($accountId: ID!) {
            remoteDamSessions(accountId: $accountId) {
                appDeploymentId appInstanceId damSessionId product version
            }
        }
        """;
    private static final String SESSION_ACCOUNT_QUERY = """
        query RemoteDamSessionAccount($damSessionId: ID!) {
            remoteDamSessionAccount(damSessionId: $damSessionId)
        }
        """;
    private static final String SEND_MUTATION = """
        mutation SendRemoteDamMessage($accountId: ID!, $damSessionId: ID!, $message: RemoteDamMessageInput!) {
            sendRemoteDamMessage(accountId: $accountId, damSessionId: $damSessionId, message: $message)
        }
        """;
    private static final String REGISTER_MUTATION = """
        mutation RegisterRemoteDamSubscription($input: RemoteDamSubscriptionInput!) {
            registerRemoteDamSubscription(input: $input)
        }
        """;
    private static final String UNREGISTER_MUTATION = """
        mutation UnregisterRemoteDamSubscription($subscriberId: String!, $subscriptionId: ID!) {
            unregisterRemoteDamSubscription(subscriberId: $subscriberId, subscriptionId: $subscriptionId)
        }
        """;

    public DDRemoteDamClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        super(serviceUrl, serviceSecret, DDRemoteDamApi.GRAPHQL_PATH, READ_TIMEOUT_MILLIS);
    }

    @NotNull
    public List<DDRemoteDamSession> getSessions(@NotNull UUID accountId) throws DDRemoteDamException {
        return readSessions(request(SESSIONS_OPERATION, SESSIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), SESSIONS_FIELD));
    }

    @NotNull
    public CompletionStage<List<DDRemoteDamSession>> getSessionsAsync(@NotNull UUID accountId) {
        return requestAsync(SESSIONS_OPERATION, SESSIONS_QUERY,
            Map.of("accountId", Objects.requireNonNull(accountId).toString()), SESSIONS_FIELD, DDRemoteDamClient::readSessions);
    }

    @NotNull
    public UUID getSessionAccount(@NotNull String damSessionId) throws DDRemoteDamException {
        return readSessionAccount(request(SESSION_ACCOUNT_OPERATION, SESSION_ACCOUNT_QUERY,
            sessionAccountVariables(damSessionId), SESSION_ACCOUNT_FIELD));
    }

    @NotNull
    public CompletionStage<UUID> getSessionAccountAsync(@NotNull String damSessionId) {
        return requestAsync(SESSION_ACCOUNT_OPERATION, SESSION_ACCOUNT_QUERY,
            sessionAccountVariables(damSessionId), SESSION_ACCOUNT_FIELD, DDRemoteDamClient::readSessionAccount);
    }

    public boolean sendMessage(@NotNull UUID accountId, @NotNull String damSessionId, @NotNull DDRemoteDamMessage message)
            throws DDRemoteDamException {
        return readBoolean(request(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, damSessionId, message), SEND_FIELD));
    }

    @NotNull
    public CompletionStage<Boolean> sendMessageAsync(
        @NotNull UUID accountId,
        @NotNull String damSessionId,
        @NotNull DDRemoteDamMessage message
    ) {
        return requestAsync(SEND_OPERATION, SEND_MUTATION, messageVariables(accountId, damSessionId, message), SEND_FIELD,
            DDRemoteDamGraphQlClient::readBoolean);
    }

    public int registerSubscription(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String damSessionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) throws DDRemoteDamException {
        return readLease(request("RegisterRemoteDamSubscription", REGISTER_MUTATION,
            subscriptionVariables(subscriberId, subscriptionId, accountId, damSessionId, topic, callbackUrl, callbackSecret),
            "registerRemoteDamSubscription"));
    }

    @NotNull
    public CompletionStage<Integer> registerSubscriptionAsync(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String damSessionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) {
        return requestAsync("RegisterRemoteDamSubscription", REGISTER_MUTATION,
            subscriptionVariables(subscriberId, subscriptionId, accountId, damSessionId, topic, callbackUrl, callbackSecret),
            "registerRemoteDamSubscription", DDRemoteDamClient::readLease);
    }

    public boolean unregisterSubscription(@NotNull String subscriberId, @NotNull UUID subscriptionId) throws DDRemoteDamException {
        return readBoolean(request("UnregisterRemoteDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterRemoteDamSubscription"));
    }

    @NotNull
    public CompletionStage<Boolean> unregisterSubscriptionAsync(@NotNull String subscriberId, @NotNull UUID subscriptionId) {
        return requestAsync("UnregisterRemoteDamSubscription", UNREGISTER_MUTATION,
            unsubscriptionVariables(subscriberId, subscriptionId), "unregisterRemoteDamSubscription",
            DDRemoteDamGraphQlClient::readBoolean);
    }

    @NotNull
    private static List<DDRemoteDamSession> readSessions(@NotNull JsonElement result) throws DDRemoteDamException {
        if (!result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDamSession> sessions = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            JsonObject session = readObject(value);
            sessions.add(new DDRemoteDamSession(
                readString(session, "appDeploymentId"), readString(session, "appInstanceId"),
                readString(session, "damSessionId"), readString(session, "product"), readString(session, "version")
            ));
        }
        return List.copyOf(sessions);
    }

    @NotNull
    private static UUID readSessionAccount(@NotNull JsonElement result) throws DDRemoteDamException {
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

    private static int readLease(@NotNull JsonElement result) throws DDRemoteDamException {
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse();
        }
        try {
            int leaseSeconds = result.getAsBigDecimal().intValueExact();
            if (leaseSeconds >= DDRemoteDamApi.MIN_SUBSCRIPTION_LEASE_SECONDS
                && leaseSeconds <= DDRemoteDamApi.MAX_SUBSCRIPTION_LEASE_SECONDS) {
                return leaseSeconds;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }

    @NotNull
    private static Map<String, ?> sessionAccountVariables(@NotNull String damSessionId) {
        if (damSessionId.isBlank() || damSessionId.length() > 256) {
            throw new IllegalArgumentException("Invalid session ID");
        }
        return Map.of("damSessionId", damSessionId);
    }

    @NotNull
    private static Map<String, ?> messageVariables(
        @NotNull UUID accountId,
        @NotNull String damSessionId,
        @NotNull DDRemoteDamMessage message
    ) {
        if (damSessionId.isBlank()) {
            throw new IllegalArgumentException("A session ID is required");
        }
        return Map.of(
            "accountId", Objects.requireNonNull(accountId).toString(), "damSessionId", damSessionId,
            "message", Objects.requireNonNull(message)
        );
    }

    @NotNull
    private static Map<String, ?> subscriptionVariables(
        @NotNull String subscriberId,
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @Nullable String damSessionId,
        @NotNull String topic,
        @NotNull String callbackUrl,
        @NotNull String callbackSecret
    ) {
        requireSubscriberId(subscriberId);
        requireText(topic);
        if (damSessionId != null) {
            requireText(damSessionId);
        }
        if (!DDRemoteDamApi.isValidServiceUrl(callbackUrl)) {
            throw new IllegalArgumentException("Invalid Remote DAM callback URL");
        }
        if (!DDRemoteDamApi.isValidServiceSecret(callbackSecret)) {
            throw new IllegalArgumentException("Invalid Remote DAM callback secret");
        }
        Map<String, Object> input = new HashMap<>();
        input.put("subscriberId", subscriberId);
        input.put("subscriptionId", Objects.requireNonNull(subscriptionId).toString());
        input.put("accountId", Objects.requireNonNull(accountId).toString());
        input.put("damSessionId", damSessionId);
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
    private static String readString(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw invalidResponse();
        }
        return value.getAsString();
    }

    private static void requireSubscriberId(@Nullable String subscriberId) {
        if (!DDRemoteDamApi.isValidSubscriberId(subscriberId)) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }

    private static void requireText(@NotNull String value) {
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid subscription metadata");
        }
    }
}
