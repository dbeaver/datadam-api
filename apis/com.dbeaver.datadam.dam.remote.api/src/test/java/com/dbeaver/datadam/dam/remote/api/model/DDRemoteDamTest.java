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
package com.dbeaver.datadam.dam.remote.api.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class DDRemoteDamTest {
    @Test
    void rejectsContradictoryOnlineAndSessionStates() {
        for (boolean online : new boolean[] {true, false}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> new DDRemoteDam(
                "dam", "deployment", "instance", "workspace", "DBeaver", "26.3", online, online ? null : "session",
                Instant.EPOCH, Instant.EPOCH, null
            ));
        }
    }
}
