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
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDam;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamAccountTraffic;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamHandshakeRequest;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamHistorySession;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamMessage;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSession;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamTraffic;
import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamTrafficDetails;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class DDRemoteDamClient extends DDRemoteDamGraphQlClient {
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final String ACCOUNT_ID = "accountId";
    private static final String DAM_ID = "damId";
    private static final String CLIENT_DAM_ID = "clientDamId";
    private static final String DAM_NAME = "damName";
    private static final String DAMS_FIELD = "remoteDams";
    private static final String DAM_FIELD = "remoteDam";
    private static final String DAM_BY_CLIENT_ID_FIELD = "remoteDamByClientId";
    private static final String RENAME_FIELD = "renameRemoteDam";
    private static final String RENAME_BY_CLIENT_ID_FIELD = "renameRemoteDamByClientId";
    private static final String DAM_HISTORY_FIELD = "remoteDamHistory";
    private static final String SESSIONS_FIELD = "remoteDamSessions";
    private static final String SESSION_ACCOUNT_FIELD = "remoteDamSessionAccount";
    private static final String SEND_FIELD = "sendRemoteDamMessage";
    private static final String DAMS_OPERATION = "RemoteDams";
    private static final String DAM_OPERATION = "RemoteDam";
    private static final String DAM_BY_CLIENT_ID_OPERATION = "RemoteDamByClientId";
    private static final String RENAME_OPERATION = "RenameRemoteDam";
    private static final String RENAME_BY_CLIENT_ID_OPERATION = "RenameRemoteDamByClientId";
    private static final String DAM_HISTORY_OPERATION = "RemoteDamHistory";
    private static final String SESSIONS_OPERATION = "RemoteDamSessions";
    private static final String SESSION_ACCOUNT_OPERATION = "RemoteDamSessionAccount";
    private static final String SEND_OPERATION = "SendRemoteDamMessage";
    private static final String DAMS_QUERY = """
        query RemoteDams($accountId: ID!) {
            remoteDams(accountId: $accountId) {
                damId appDeploymentId appInstanceId workspaceId product version remoteAddress online damSessionId
                firstConnectedAt lastConnectedAt lastDisconnectedAt
                clientDamId sessionCount applications { product version } remoteAddresses damName
            }
        }
        """;
    private static final String DAM_QUERY = """
        query RemoteDam($accountId: ID!, $damId: ID!) {
            remoteDam(accountId: $accountId, damId: $damId) {
                damId appDeploymentId appInstanceId workspaceId product version remoteAddress online damSessionId
                firstConnectedAt lastConnectedAt lastDisconnectedAt
                clientDamId sessionCount applications { product version } remoteAddresses damName
            }
        }
        """;
    private static final String DAM_BY_CLIENT_ID_QUERY = """
        query RemoteDamByClientId($accountId: ID!, $clientDamId: String!) {
            remoteDamByClientId(accountId: $accountId, clientDamId: $clientDamId) {
                damId appDeploymentId appInstanceId workspaceId product version remoteAddress online damSessionId
                firstConnectedAt lastConnectedAt lastDisconnectedAt
                clientDamId sessionCount applications { product version } remoteAddresses damName
            }
        }
        """;
    private static final String RENAME_MUTATION = """
        mutation RenameRemoteDam($accountId: ID!, $damId: ID!, $damName: String!) {
            renameRemoteDam(accountId: $accountId, damId: $damId, damName: $damName) {
                damId appDeploymentId appInstanceId workspaceId product version remoteAddress online damSessionId
                firstConnectedAt lastConnectedAt lastDisconnectedAt
                clientDamId sessionCount applications { product version } remoteAddresses damName
            }
        }
        """;
    private static final String RENAME_BY_CLIENT_ID_MUTATION = """
        mutation RenameRemoteDamByClientId($accountId: ID!, $clientDamId: String!, $damName: String!) {
            renameRemoteDamByClientId(accountId: $accountId, clientDamId: $clientDamId, damName: $damName) {
                damId appDeploymentId appInstanceId workspaceId product version remoteAddress online damSessionId
                firstConnectedAt lastConnectedAt lastDisconnectedAt
                clientDamId sessionCount applications { product version } remoteAddresses damName
            }
        }
        """;
    private static final String DAM_HISTORY_QUERY = """
        query RemoteDamHistory($accountId: ID!, $damId: ID!) {
            remoteDamHistory(accountId: $accountId, damId: $damId) {
                damSessionId appDeploymentId appInstanceId product version remoteAddress online connectedAt endedAt durationSeconds
            }
        }
        """;
    private static final String ACCOUNT_TRAFFIC_QUERY = """
        query RemoteDamAccountTraffic($accountId: ID!) {
            remoteDamAccountTraffic(accountId: $accountId) {
                total { inboundBytes outboundBytes }
                dams { damId traffic { inboundBytes outboundBytes } }
            }
        }
        """;
    private static final String DAM_TRAFFIC_QUERY = """
        query RemoteDamTraffic($accountId: ID!, $damId: ID!) {
            remoteDamTraffic(accountId: $accountId, damId: $damId) {
                total { inboundBytes outboundBytes }
                sessions { damSessionId traffic { inboundBytes outboundBytes } }
            }
        }
        """;
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
    public List<DDRemoteDam> getDams(@NotNull UUID accountId) throws DDRemoteDamException {
        return readDams(request(DAMS_OPERATION, DAMS_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString()), DAMS_FIELD));
    }

    @NotNull
    public CompletionStage<List<DDRemoteDam>> getDamsAsync(@NotNull UUID accountId) {
        return requestAsync(DAMS_OPERATION, DAMS_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString()), DAMS_FIELD, DDRemoteDamClient::readDams);
    }

    @Nullable
    public DDRemoteDam getDam(@NotNull UUID accountId, @NotNull UUID damId) throws DDRemoteDamException {
        JsonElement result = request(DAM_OPERATION, DAM_QUERY, damVariables(accountId, damId), DAM_FIELD);
        return result.isJsonNull() ? null : readDam(result);
    }

    @Nullable
    public DDRemoteDam getDamByClientId(@NotNull UUID accountId, @NotNull String clientDamId) throws DDRemoteDamException {
        if (clientDamId.isBlank() || clientDamId.length() > DDRemoteDamHandshakeRequest.MAX_CLIENT_DAM_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid client Dam ID");
        }
        JsonElement result = request(DAM_BY_CLIENT_ID_OPERATION, DAM_BY_CLIENT_ID_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString(), CLIENT_DAM_ID, clientDamId), DAM_BY_CLIENT_ID_FIELD);
        return result.isJsonNull() ? null : readDam(result);
    }

    @Nullable
    public DDRemoteDam renameDam(@NotNull UUID accountId, @NotNull UUID damId, @NotNull String damName) throws DDRemoteDamException {
        JsonElement result = request(RENAME_OPERATION, RENAME_MUTATION,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString(), DAM_ID, Objects.requireNonNull(damId).toString(),
                DAM_NAME, DDRemoteDam.normalizeName(damName)), RENAME_FIELD);
        return result.isJsonNull() ? null : readDam(result);
    }

    @Nullable
    public DDRemoteDam renameDamByClientId(
        @NotNull UUID accountId,
        @NotNull String clientDamId,
        @NotNull String damName
    ) throws DDRemoteDamException {
        if (clientDamId.isBlank() || clientDamId.length() > DDRemoteDamHandshakeRequest.MAX_CLIENT_DAM_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid client Dam ID");
        }
        JsonElement result = request(RENAME_BY_CLIENT_ID_OPERATION, RENAME_BY_CLIENT_ID_MUTATION,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString(), CLIENT_DAM_ID, clientDamId,
                DAM_NAME, DDRemoteDam.normalizeName(damName)), RENAME_BY_CLIENT_ID_FIELD);
        return result.isJsonNull() ? null : readDam(result);
    }

    @NotNull
    public List<DDRemoteDamHistorySession> getDamHistory(@NotNull UUID accountId, @NotNull UUID damId) throws DDRemoteDamException {
        return readDamHistory(request(DAM_HISTORY_OPERATION, DAM_HISTORY_QUERY, damVariables(accountId, damId), DAM_HISTORY_FIELD));
    }

    @NotNull
    public DDRemoteDamAccountTraffic getAccountTraffic(@NotNull UUID accountId) throws DDRemoteDamException {
        JsonObject result = readObject(request("RemoteDamAccountTraffic", ACCOUNT_TRAFFIC_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString()), "remoteDamAccountTraffic"));
        JsonElement values = result.get("dams");
        if (values == null || !values.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDamAccountTraffic.Dam> dams = new ArrayList<>();
        for (JsonElement value : values.getAsJsonArray()) {
            JsonObject dam = readObject(value);
            dams.add(new DDRemoteDamAccountTraffic.Dam(readString(dam, DAM_ID), readTraffic(dam, "traffic")));
        }
        return new DDRemoteDamAccountTraffic(readTraffic(result, "total"), dams);
    }

    @Nullable
    public DDRemoteDamTrafficDetails getDamTraffic(@NotNull UUID accountId, @NotNull UUID damId) throws DDRemoteDamException {
        JsonElement value = request("RemoteDamTraffic", DAM_TRAFFIC_QUERY, damVariables(accountId, damId), "remoteDamTraffic");
        if (value.isJsonNull()) {
            return null;
        }
        JsonObject result = readObject(value);
        JsonElement values = result.get("sessions");
        if (values == null || !values.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDamTrafficDetails.Session> sessions = new ArrayList<>();
        for (JsonElement entry : values.getAsJsonArray()) {
            JsonObject session = readObject(entry);
            sessions.add(new DDRemoteDamTrafficDetails.Session(readString(session, "damSessionId"), readTraffic(session, "traffic")));
        }
        return new DDRemoteDamTrafficDetails(readTraffic(result, "total"), sessions);
    }

    @NotNull
    public List<DDRemoteDamSession> getSessions(@NotNull UUID accountId) throws DDRemoteDamException {
        return readSessions(request(SESSIONS_OPERATION, SESSIONS_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString()), SESSIONS_FIELD));
    }

    @NotNull
    public CompletionStage<List<DDRemoteDamSession>> getSessionsAsync(@NotNull UUID accountId) {
        return requestAsync(SESSIONS_OPERATION, SESSIONS_QUERY,
            Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString()), SESSIONS_FIELD, DDRemoteDamClient::readSessions);
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
    private static List<DDRemoteDam> readDams(@NotNull JsonElement result) throws DDRemoteDamException {
        if (!result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDam> dams = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            dams.add(readDam(value));
        }
        return List.copyOf(dams);
    }

    @NotNull
    private static DDRemoteDam readDam(@NotNull JsonElement value) throws DDRemoteDamException {
        JsonObject dam = readObject(value);
        long sessionCount = readNonNegativeLong(dam, "sessionCount");
        if (sessionCount > Integer.MAX_VALUE) {
            throw invalidResponse();
        }
        try {
            return new DDRemoteDam(
                readString(dam, DAM_ID), readString(dam, "appDeploymentId"), readString(dam, "appInstanceId"),
                readString(dam, "workspaceId"), readString(dam, "product"), readString(dam, "version"),
                readNullableString(dam, "remoteAddress"), readBooleanField(dam, "online"),
                readNullableString(dam, "damSessionId"), readInstant(dam, "firstConnectedAt"), readInstant(dam, "lastConnectedAt"),
                readNullableInstant(dam, "lastDisconnectedAt"), readNullableString(dam, CLIENT_DAM_ID), (int) sessionCount,
                readApplications(dam.get("applications")), readRemoteAddresses(dam.get("remoteAddresses")),
                readNullableString(dam, DAM_NAME)
            );
        } catch (IllegalArgumentException e) {
            throw invalidResponse();
        }
    }

    @NotNull
    private static List<DDRemoteDam.Application> readApplications(@Nullable JsonElement result) throws DDRemoteDamException {
        if (result == null || !result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDam.Application> applications = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            JsonObject application = readObject(value);
            applications.add(new DDRemoteDam.Application(readString(application, "product"), readString(application, "version")));
        }
        return List.copyOf(applications);
    }

    @NotNull
    private static List<String> readRemoteAddresses(@Nullable JsonElement result) throws DDRemoteDamException {
        if (result == null || !result.isJsonArray()) {
            throw invalidResponse();
        }
        List<String> addresses = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
                throw invalidResponse();
            }
            addresses.add(value.getAsString());
        }
        return List.copyOf(addresses);
    }

    @NotNull
    private static List<DDRemoteDamHistorySession> readDamHistory(@NotNull JsonElement result) throws DDRemoteDamException {
        if (!result.isJsonArray()) {
            throw invalidResponse();
        }
        List<DDRemoteDamHistorySession> sessions = new ArrayList<>();
        for (JsonElement value : result.getAsJsonArray()) {
            JsonObject session = readObject(value);
            try {
                sessions.add(new DDRemoteDamHistorySession(
                    readString(session, "damSessionId"), readNullableString(session, "appDeploymentId"),
                    readNullableString(session, "appInstanceId"), readString(session, "product"), readString(session, "version"),
                    readNullableString(session, "remoteAddress"), readBooleanField(session, "online"),
                    readInstant(session, "connectedAt"), readNullableInstant(session, "endedAt"),
                    readNonNegativeLong(session, "durationSeconds")
                ));
            } catch (IllegalArgumentException e) {
                throw invalidResponse();
            }
        }
        return List.copyOf(sessions);
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
    private static DDRemoteDamTraffic readTraffic(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        if (value == null) {
            throw invalidResponse();
        }
        JsonObject traffic = readObject(value);
        return new DDRemoteDamTraffic(readNonNegativeLong(traffic, "inboundBytes"), readNonNegativeLong(traffic, "outboundBytes"));
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
    private static Map<String, ?> damVariables(@NotNull UUID accountId, @NotNull UUID damId) {
        return Map.of(ACCOUNT_ID, Objects.requireNonNull(accountId).toString(), DAM_ID, Objects.requireNonNull(damId).toString());
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
            ACCOUNT_ID, Objects.requireNonNull(accountId).toString(), "damSessionId", damSessionId,
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
        input.put(ACCOUNT_ID, Objects.requireNonNull(accountId).toString());
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

    @Nullable
    private static String readNullableString(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : readString(object, field);
    }

    private static boolean readBooleanField(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        if (value == null) {
            throw invalidResponse();
        }
        return readBoolean(value);
    }

    private static long readNonNegativeLong(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidResponse();
        }
        try {
            long number = value.getAsBigDecimal().longValueExact();
            if (number >= 0) {
                return number;
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalidResponse();
        }
        throw invalidResponse();
    }

    @NotNull
    private static Instant readInstant(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        try {
            return Instant.parse(readString(object, field));
        } catch (DateTimeParseException e) {
            throw invalidResponse();
        }
    }

    @Nullable
    private static Instant readNullableInstant(@NotNull JsonObject object, @NotNull String field) throws DDRemoteDamException {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : readInstant(object, field);
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
