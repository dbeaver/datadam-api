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

import com.dbeaver.datadam.dam.remote.api.DDRemoteDamProtocol;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DDRemoteDamMessageTest {
    @Test
    void isolatesThePayloadFromChangesToTheOriginalObject() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("name", "original");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", project);
        DDRemoteDamMessage message = new DDRemoteDamMessage("projects", "updated", payload);

        project.put("name", "changed");

        assertEquals(Map.of("name", "original"), message.payload().get("project"));
    }

    @Test
    void isolatesListenersFromEachOthersPayloadChanges() {
        Map<String, Object> payload = Map.of("projectId", "project-1");
        DDRemoteDamMessage message = new DDRemoteDamMessage("projects", "updated", payload);

        assertThrows(UnsupportedOperationException.class, () -> message.payload().put("projectId", "changed"));

        assertEquals("project-1", message.payload().get("projectId"));
    }

    @Test
    void rejectsBlankRoutingFields() {
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamMessage(" ", "updated", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamMessage("projects", " ", Map.of()));
    }

    @Test
    void preservesNullsNestedListsAndExactNumbersWithoutExposingMutableValues() {
        List<Object> values = new ArrayList<>();
        values.add(null);
        values.add(new BigDecimal("9007199254740993"));
        values.add(new BigDecimal("0.12345678901234567890123456789"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("values", values);
        payload.put("nothing", null);
        payload.put("flag", true);
        DDRemoteDamMessage message = new DDRemoteDamMessage("topic", "event", payload);
        values.clear();
        payload.clear();

        assertEquals(message, DDRemoteDamProtocol.decode(DDRemoteDamProtocol.encode(message)));
        Assertions.assertTrue(message.payload().containsKey("nothing"));
        Assertions.assertNull(message.payload().get("nothing"));
        List<?> stored = (List<?>) message.payload().get("values");
        assertEquals(new BigDecimal("9007199254740993"), stored.get(1));
        assertThrows(UnsupportedOperationException.class, stored::clear);
    }

    @Test
    void rejectsNonJsonValuesAndCycles() {
        for (Object value : List.of(new Object(), Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamMessage("topic", "event", Map.of("value", value)));
        }
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamMessage("topic", "event", cyclic));
        List<Object> list = new ArrayList<>();
        list.add(list);
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamMessage("topic", "event", Map.of("cycle", list)));
        assertThrows(IllegalArgumentException.class,
            () -> new DDRemoteDamMessage("topic", "event", Map.of("invalidKeys", Map.of(1, "value"))));
    }

    @Test
    void rejectsMissingSessionIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamSessionInfo(" ", "run-1", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamSessionInfo("DI_remoteDam", " ", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamSessionInfo("DI_remoteDam", "run-1", " "));
    }

    @Test
    void rejectsInvalidSessionSettings() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamSessionSettings(null, DDRemoteDamSettings.EMPTY));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new DDRemoteDamSessionSettings(" ", DDRemoteDamSettings.EMPTY));
        Assertions.assertThrows(NullPointerException.class, () -> new DDRemoteDamSessionSettings("session", null));
        Assertions.assertEquals(DDRemoteDamSettings.EMPTY, new DDRemoteDamSessionSettings("session", DDRemoteDamSettings.EMPTY).settings());
    }
}
