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

import com.dbeaver.datadam.dam.remote.api.model.DDRemoteDamSessionInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDRemoteDamChannelStatusTest {
    private static final DDRemoteDamSessionInfo SESSION = new DDRemoteDamSessionInfo("deployment", "run", "socket");

    @Test
    void onlyConnectedStatusIsReadyAndCarriesSessionInformation() {
        for (DDRemoteDamChannelState state : DDRemoteDamChannelState.values()) {
            boolean connected = state == DDRemoteDamChannelState.CONNECTED;
            DDRemoteDamChannelStatus status = new DDRemoteDamChannelStatus(state, connected ? SESSION : null);

            Assertions.assertEquals(connected, status.isReady());
            Assertions.assertEquals(connected ? SESSION : null, status.session());
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DDRemoteDamChannelStatus(state, connected ? null : SESSION));
        }
    }

    @Test
    void rejectsMissingState() {
        Assertions.assertThrows(NullPointerException.class, () -> new DDRemoteDamChannelStatus(null, null));
    }
}
