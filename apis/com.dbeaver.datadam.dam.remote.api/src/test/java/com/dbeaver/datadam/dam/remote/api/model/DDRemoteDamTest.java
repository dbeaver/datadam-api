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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class DDRemoteDamTest {
    @Test
    void normalizesNamesWithoutChangingInternalWhitespace() {
        String name = "Development  workstation";
        String padded = "\u2003\t" + name + "\n ";

        Assertions.assertEquals(name, DDRemoteDam.normalizeName(padded));
        Assertions.assertEquals(name, dam(padded).damName());
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRemoteDam.normalizeName(" \t\u2003\n"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> dam(""));
    }

    @Test
    void limitsNamesByUnicodeCodePointsAfterStrippingWhitespace() {
        String name = "\uD83D\uDE80".repeat(DDRemoteDam.MAX_DAM_NAME_LENGTH);

        Assertions.assertEquals(name, dam(" " + name + " ").damName());
        Assertions.assertThrows(IllegalArgumentException.class, () -> DDRemoteDam.normalizeName(name + "x"));
    }

    @Test
    void retainsUnnamedDamsThroughBothConstructors() {
        DDRemoteDam legacy = new DDRemoteDam(
            "dam", "deployment", "instance", "workspace", "DBeaver", "26.3", null, false, null,
            Instant.EPOCH, Instant.EPOCH, null, "client-dam", 0, List.of(), List.of()
        );

        Assertions.assertNull(legacy.damName());
        Assertions.assertEquals(legacy, dam(null));
    }

    @Test
    void rejectsContradictoryOnlineAndSessionStates() {
        for (boolean online : new boolean[] {true, false}) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> new DDRemoteDam(
                "dam", "deployment", "instance", "workspace", "DBeaver", "26.3", null, online, online ? null : "session",
                Instant.EPOCH, Instant.EPOCH, null, "client-dam", online ? 1 : 0, List.of(), List.of()
            ));
        }
    }

    @NotNull
    private static DDRemoteDam dam(@Nullable String damName) {
        return new DDRemoteDam(
            "dam", "deployment", "instance", "workspace", "DBeaver", "26.3", null, false, null,
            Instant.EPOCH, Instant.EPOCH, null, "client-dam", 0, List.of(), List.of(), damName
        );
    }
}
