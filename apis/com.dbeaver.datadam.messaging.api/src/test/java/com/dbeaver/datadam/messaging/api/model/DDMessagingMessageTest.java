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

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DDMessagingMessageTest {
    @Test
    void isolatesThePayloadFromChangesToTheOriginalObject() {
        JsonObject project = new JsonObject();
        project.addProperty("name", "original");
        JsonObject payload = new JsonObject();
        payload.add("project", project);
        DDMessagingMessage message = new DDMessagingMessage("projects", "updated", payload);

        project.addProperty("name", "changed");

        assertEquals("original", message.payload().getAsJsonObject("project").get("name").getAsString());
    }

    @Test
    void isolatesListenersFromEachOthersPayloadChanges() {
        JsonObject payload = new JsonObject();
        payload.addProperty("projectId", "project-1");
        DDMessagingMessage message = new DDMessagingMessage("projects", "updated", payload);

        message.payload().addProperty("projectId", "changed");

        assertEquals("project-1", message.payload().get("projectId").getAsString());
    }

    @Test
    void rejectsBlankRoutingFields() {
        assertThrows(IllegalArgumentException.class, () -> new DDMessagingMessage(" ", "updated", new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new DDMessagingMessage("projects", " ", new JsonObject()));
    }

    @Test
    void rejectsMissingConnectionIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new DDMessagingConnectionInfo(" ", "run-1", "connection-1"));
        assertThrows(IllegalArgumentException.class, () -> new DDMessagingConnectionInfo("DI_desktop", " ", "connection-1"));
        assertThrows(IllegalArgumentException.class, () -> new DDMessagingConnectionInfo("DI_desktop", "run-1", " "));
    }
}
