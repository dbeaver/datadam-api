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

import com.dbeaver.datadam.messaging.api.model.DDDamConnectionInfo;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Objects;

public record DDMessagingChannelStatus(@NotNull DDMessagingChannelState state, @Nullable DDDamConnectionInfo connection) {
    public DDMessagingChannelStatus {
        Objects.requireNonNull(state);
        if ((state == DDMessagingChannelState.CONNECTED) != (connection != null)) {
            throw new IllegalArgumentException("Only a connected channel has connection information");
        }
    }

    public boolean isReady() {
        return state == DDMessagingChannelState.CONNECTED;
    }
}
