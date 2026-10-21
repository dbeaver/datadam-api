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
package com.dbeaver.datadam.external.api.rpc;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class DDRpcServicesTest {
    @Test
    void separatesBackendServicesAndSupportsConfiguredExtensions() {
        Assertions.assertEquals("rpc.account", DDRpcServices.topic(DDRpcServices.ACCOUNT));
        Assertions.assertEquals("rpc.storage", DDRpcServices.topic(DDRpcServices.STORAGE));
        Assertions.assertEquals("rpc.other-service.v2_1", DDRpcServices.topic("other-service.v2_1"));
        Assertions.assertNotEquals(DDRpcServices.topic(DDRpcServices.ACCOUNT), DDRpcServices.topic(DDRpcServices.STORAGE));
        Assertions.assertEquals(132, DDRpcServices.topic("a".repeat(128)).length());
    }

    @Test
    void rejectsInvalidServiceIdsBeforeChoosingALane() {
        for (String value : List.of("", " ", "a b", "a/b", "a:b", "a\nb", "a\u0000b", "сервис", "a".repeat(129))) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> DDRpcServices.topic(value), value);
        }
        Assertions.assertThrows(NullPointerException.class, () -> DDRpcServices.topic(null));
    }
}
