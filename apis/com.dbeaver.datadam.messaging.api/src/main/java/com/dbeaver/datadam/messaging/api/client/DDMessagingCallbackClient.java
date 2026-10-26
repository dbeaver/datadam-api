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
import com.dbeaver.datadam.messaging.api.model.DDDamConnectionInfo;
import com.dbeaver.datadam.messaging.api.model.DDMessagingMessage;
import com.google.gson.JsonElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class DDMessagingCallbackClient extends DDMessagingGraphQlClient {
    private static final String DELIVER_MUTATION = """
        mutation DeliverExternalDamMessage($subscriptionId: ID!, $accountId: ID!,
            $connection: ExternalDamConnectionInfoInput!, $message: ExternalDamMessageInput!) {
            deliverExternalDamMessage(subscriptionId: $subscriptionId, accountId: $accountId, connection: $connection, message: $message)
        }
        """;

    public DDMessagingCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret) {
        this(serviceUrl, serviceSecret, Duration.ofSeconds(30));
    }

    public DDMessagingCallbackClient(@NotNull String serviceUrl, @NotNull String serviceSecret, @NotNull Duration deliveryTimeout) {
        super(serviceUrl, serviceSecret, DDMessagingApi.CALLBACK_GRAPHQL_PATH, timeoutMillis(deliveryTimeout));
    }

    public void deliver(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDDamConnectionInfo connection,
        @NotNull DDMessagingMessage message
    ) throws DDMessagingException {
        readDeliveryResult(request("DeliverExternalDamMessage", DELIVER_MUTATION,
            deliveryVariables(subscriptionId, accountId, connection, message), "deliverExternalDamMessage"));
    }

    @NotNull
    public CompletionStage<Void> deliverAsync(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDDamConnectionInfo connection,
        @NotNull DDMessagingMessage message
    ) {
        return requestAsync("DeliverExternalDamMessage", DELIVER_MUTATION,
            deliveryVariables(subscriptionId, accountId, connection, message), "deliverExternalDamMessage",
            DDMessagingCallbackClient::readDeliveryResult);
    }

    @NotNull
    private static Map<String, ?> deliveryVariables(
        @NotNull UUID subscriptionId,
        @NotNull UUID accountId,
        @NotNull DDDamConnectionInfo connection,
        @NotNull DDMessagingMessage message
    ) {
        return Map.of(
            "subscriptionId", Objects.requireNonNull(subscriptionId).toString(),
            "accountId", Objects.requireNonNull(accountId).toString(),
            "connection", Objects.requireNonNull(connection), "message", Objects.requireNonNull(message)
        );
    }

    @Nullable
    private static Void readDeliveryResult(@NotNull JsonElement result) throws DDMessagingException {
        if (!readBoolean(result)) {
            throw new DDMessagingException("External DAM callback was not accepted");
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
