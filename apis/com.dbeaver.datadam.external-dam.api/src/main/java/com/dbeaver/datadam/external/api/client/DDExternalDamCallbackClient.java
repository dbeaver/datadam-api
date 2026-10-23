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
import com.google.gson.JsonElement;
import org.jkiss.code.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DDExternalDamCallbackClient extends DDExternalDamGraphQlClient {
    private static final String DELIVER_MUTATION = """
        mutation DeliverExternalDamMessage($subscriptionId: ID!, $accountId: ID!,
            $connection: ExternalDamConnectionInfoInput!, $message: ExternalDamMessageInput!) {
            deliverExternalDamMessage(subscriptionId: $subscriptionId, accountId: $accountId, connection: $connection, message: $message)
        }
        """;

    public DDExternalDamCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        this(serviceUrl, serviceSecret, Duration.ofSeconds(5));
    }

    public DDExternalDamCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret, @NotNull Duration deliveryTimeout) {
        super(serviceUrl, serviceSecret, DDExternalDamApi.CALLBACK_GRAPHQL_PATH, timeoutMillis(deliveryTimeout));
    }

    public void deliver(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDConnectionInfo connection,
        @NotNull DDMessage message
    ) throws DDExternalDamException {
        JsonElement result = request("DeliverExternalDamMessage", DELIVER_MUTATION, Map.of(
            "subscriptionId", Objects.requireNonNull(subscriptionId).toString(),
            "accountId", Objects.requireNonNull(accountId).toString(),
            "connection", Objects.requireNonNull(connection), "message", Objects.requireNonNull(message)
        ), "deliverExternalDamMessage");
        if (!result.isJsonPrimitive() || !result.getAsJsonPrimitive().isBoolean()) {
            throw invalidResponse();
        }
        if (!result.getAsBoolean()) {
            throw new DDExternalDamException("External DAM callback was not accepted");
        }
    }

    private static int timeoutMillis(@NotNull Duration timeout) {
        if (timeout.isNegative() || timeout.compareTo(Duration.ofMillis(1)) < 0
            || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Invalid callback delivery timeout");
        }
        return (int) timeout.toMillis();
    }
}
