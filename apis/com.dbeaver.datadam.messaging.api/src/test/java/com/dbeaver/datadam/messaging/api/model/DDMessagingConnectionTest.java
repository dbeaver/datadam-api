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
package com.dbeaver.datadam.messaging.api.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDMessagingConnectionTest {
    @Test
    void preservesSeparateDeploymentRunAndSocketIdentities() {
        DDMessagingConnection connection = new DDMessagingConnection("deployment", "run", "socket", "DBeaver", "26.2");

        Assertions.assertEquals("deployment", connection.deploymentId());
        Assertions.assertEquals("run", connection.applicationRunId());
        Assertions.assertEquals("socket", connection.connectionId());
        Assertions.assertEquals("DBeaver", connection.product());
        Assertions.assertEquals("26.2", connection.version());
        Assertions.assertNotEquals(connection, new DDMessagingConnection("deployment", "run", "another-socket", "DBeaver", "26.2"));
    }

    @Test
    void rejectsMissingOrBlankMetadataInEveryField() {
        for (int field = 0; field < 5; field++) {
            for (String invalid : new String[] {null, "", " ", "\t\n"}) {
                String[] values = {"deployment", "run", "socket", "DBeaver", "26.2"};
                values[field] = invalid;

                Assertions.assertThrows(IllegalArgumentException.class, () ->
                    new DDMessagingConnection(values[0], values[1], values[2], values[3], values[4]));
            }
        }
    }
}
