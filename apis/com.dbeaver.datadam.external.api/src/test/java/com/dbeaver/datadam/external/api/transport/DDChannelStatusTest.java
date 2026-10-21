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
package com.dbeaver.datadam.external.api.transport;

import com.dbeaver.datadam.external.api.model.DDConnectionInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDChannelStatusTest {
    private static final DDConnectionInfo CONNECTION = new DDConnectionInfo("deployment", "run", "socket");

    @Test
    void onlyConnectedStatusIsReadyAndCarriesConnectionInformation() {
        for (DDChannelState state : DDChannelState.values()) {
            boolean connected = state == DDChannelState.CONNECTED;
            DDChannelStatus status = new DDChannelStatus(state, connected ? CONNECTION : null);

            Assertions.assertEquals(connected, status.isReady());
            Assertions.assertEquals(connected ? CONNECTION : null, status.connection());
            Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DDChannelStatus(state, connected ? null : CONNECTION));
        }
    }

    @Test
    void rejectsMissingState() {
        Assertions.assertThrows(NullPointerException.class, () -> new DDChannelStatus(null, null));
    }
}
