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
import com.google.gson.JsonElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class DDRemoteDamCallbackClient extends DDRemoteDamGraphQlClient {
    private static final String DELIVER_FIELD = "deliverExternalDamMessage";
    private static final String DELIVER_OPERATION = "DeliverExternalDamMessage";
    private static final String DELIVER_MUTATION = """
        mutation DeliverExternalDamMessage($subscriptionId: ID!, $accountId: ID!,
            $session: ExternalDamSessionInfoInput!, $message: ExternalDamMessageInput!) {
            deliverExternalDamMessage(subscriptionId: $subscriptionId, accountId: $accountId, session: $session, message: $message)
        }
        """;

    public DDRemoteDamCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        this(serviceUrl, serviceSecret, Duration.ofSeconds(30));
    }

    public DDRemoteDamCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret, @NotNull Duration deliveryTimeout) {
        super(serviceUrl, serviceSecret, DDRemoteDamApi.CALLBACK_GRAPHQL_PATH, timeoutMillis(deliveryTimeout));
    }

    public void deliver(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDRemoteDamSessionInfo session,
        @NotNull DDRemoteDamMessage message
    ) throws DDRemoteDamException {
        readDeliveryResult(request(DELIVER_OPERATION, DELIVER_MUTATION,
            deliveryVariables(subscriptionId, accountId, session, message), DELIVER_FIELD));
    }

    @NotNull
    public CompletionStage<Void> deliverAsync(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDRemoteDamSessionInfo session,
        @NotNull DDRemoteDamMessage message
    ) {
        return requestAsync(DELIVER_OPERATION, DELIVER_MUTATION,
            deliveryVariables(subscriptionId, accountId, session, message), DELIVER_FIELD,
            DDRemoteDamCallbackClient::readDeliveryResult);
    }

    @NotNull
    private static Map<String, ?> deliveryVariables(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDRemoteDamSessionInfo session,
        @NotNull DDRemoteDamMessage message
    ) {
        return Map.of(
            "subscriptionId", Objects.requireNonNull(subscriptionId).toString(),
            "accountId", Objects.requireNonNull(accountId).toString(),
            "session", Objects.requireNonNull(session), "message", Objects.requireNonNull(message)
        );
    }

    @Nullable
    private static Void readDeliveryResult(@NotNull JsonElement result) throws DDRemoteDamException {
        if (!readBoolean(result)) {
            throw new DDRemoteDamException("External DAM callback was not accepted");
        }
        return null;
    }

    private static int timeoutMillis(@NotNull Duration timeout) {
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMillis(1)) < 0
            || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Invalid callback delivery timeout");
        }
        return (int) timeout.toMillis();
    }
}
