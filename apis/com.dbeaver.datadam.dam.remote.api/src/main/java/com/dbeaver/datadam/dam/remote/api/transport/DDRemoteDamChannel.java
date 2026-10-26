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
package com.dbeaver.datadam.dam.remote.api.transport;

import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamMessage;
import org.jkiss.code.NotNull;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Provides asynchronous DataDam messaging and session-state observation. */
public interface DDRemoteDamChannel extends AutoCloseable {

    @NotNull
    DDRemoteDamChannelStatus getStatus();

    @NotNull
    DDRemoteDamSubscription observeState(@NotNull Consumer<DDRemoteDamChannelStatus> observer);

    @NotNull
    CompletionStage<Void> send(@NotNull DDRemoteDamMessage message);

    @NotNull
    DDRemoteDamSubscription subscribe(@NotNull String topic, @NotNull DDRemoteDamMessageListener listener);

    @NotNull
    DDRemoteDamSubscription addSessionListener(@NotNull DDRemoteDamSessionListener listener);

    @Override
    void close();
}
