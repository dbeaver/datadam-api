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

import com.dbeaver.datadam.messaging.api.model.DDMessagingConnectionInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDMessagingChannelStatusTest {
    private static final DDMessagingConnectionInfo CONNECTION = new DDMessagingConnectionInfo("deployment", "run", "socket");

    @Test
    void onlyConnectedStatusIsReadyAndCarriesConnectionInformation() {
        for (DDMessagingChannelState state : DDMessagingChannelState.values()) {
            boolean connected = state == DDMessagingChannelState.CONNECTED;
            DDMessagingChannelStatus status = new DDMessagingChannelStatus(state, connected ? CONNECTION : null);

            Assertions.assertEquals(connected, status.isReady());
            Assertions.assertEquals(connected ? CONNECTION : null, status.connection());
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DDMessagingChannelStatus(state, connected ? null : CONNECTION));
        }
    }

    @Test
    void rejectsMissingState() {
        Assertions.assertThrows(NullPointerException.class, () -> new DDMessagingChannelStatus(null, null));
    }
}
