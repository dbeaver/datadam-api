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
package com.dbeaver.datadam.ai.api;

import com.dbeaver.datadam.ai.api.model.DDAiFunctionCall;
import com.dbeaver.datadam.ai.api.model.DDAiFunctionDescriptor;
import com.dbeaver.datadam.ai.api.model.DDAiFunctionResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDAiFunctionContractTest {
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @Test
    void preservesNestedSchemaAndOptionalDescription() {
        var schema = JsonParser.parseString("""
            {"type":"object","properties":{"filter":{"type":"object","properties":{"ids":{"type":"array",
            "items":{"type":"integer"}}}}},"required":["filter"],"additionalProperties":false}
            """).getAsJsonObject();
        var descriptor = new DDAiFunctionDescriptor("database_query", "Query", null, schema);
        Assertions.assertEquals(descriptor, gson.fromJson(gson.toJson(descriptor), DDAiFunctionDescriptor.class));
    }

    @Test
    void preservesNumericArgumentsWithoutDoubleConversion() {
        var arguments = JsonParser.parseString("{\"id\":9007199254740993,\"amount\":0.1234567890123456789,\"value\":null}")
            .getAsJsonObject();
        var call = new DDAiFunctionCall("database_query", arguments);
        var decoded = gson.fromJson(gson.toJson(call), DDAiFunctionCall.class);
        Assertions.assertEquals("9007199254740993", decoded.arguments().get("id").getAsString());
        Assertions.assertEquals("0.1234567890123456789", decoded.arguments().get("amount").getAsString());
        Assertions.assertTrue(decoded.arguments().get("value").isJsonNull());
    }

    @Test
    void preservesExecutionFailureAndStructuredResult() {
        var result = new DDAiFunctionResult(JsonParser.parseString("{\"message\":\"Permission denied\",\"rows\":[]}"), true);
        Assertions.assertEquals(result, gson.fromJson(gson.toJson(result), DDAiFunctionResult.class));
    }
}
