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
package com.dbeaver.messaging.requestreply;

import org.jkiss.code.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Provides asynchronous request/reply message transport for a selected peer. */
public interface RequestReplyTransport<P> {
    @NotNull
    MessageSubscription subscribe(@NotNull UUID endpointId, @NotNull Listener<P> listener) throws RequestReplyException;

    @NotNull
    CompletionStage<Void> send(@NotNull RequestReplyMessage<P> message);

    default void executeRequest(@NotNull Runnable request) {
        request.run();
    }

    /** Receives endpoint messages and disconnection notifications. */
    interface Listener<P> {
        void onMessage(@NotNull RequestReplyMessage<P> message);

        void onDisconnect();
    }
}
