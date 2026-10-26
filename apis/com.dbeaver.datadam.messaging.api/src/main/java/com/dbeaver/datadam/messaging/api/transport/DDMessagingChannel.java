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
package com.dbeaver.datadam.messaging.api.transport;

import com.dbeaver.datadam.messaging.api.model.DDMessagingMessage;
import org.jkiss.code.NotNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Provides asynchronous DataDam messaging and connection-state observation. */
public interface DDMessagingChannel extends AutoCloseable {

    @NotNull
    DDMessagingChannelStatus getStatus();

    @NotNull
    DDMessagingSubscription observeState(@NotNull Consumer<DDMessagingChannelStatus> observer);

    @NotNull
    CompletionStage<Void> send(@NotNull DDMessagingMessage message);

    @NotNull
    DDMessagingSubscription subscribe(@NotNull String topic, @NotNull DDMessagingMessageListener listener);

    @NotNull
    DDMessagingSubscription addConnectionListener(@NotNull DDMessagingConnectionListener listener);

    @Override
    void close();
}
