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
package com.dbeaver.datadam.mcp.api;

import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionCall;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionDescriptor;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionParameter;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class DDMcpFunctionContractTest {
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    @Test
    void preservesTypedParametersAndOptionalDescription() {
        var descriptor = new DDMcpFunctionDescriptor("database_query", "Query", null, List.of(
            new DDMcpFunctionParameter("query", DDMcpFunctionParameter.Type.STRING, "SQL query", true, null, null),
            new DDMcpFunctionParameter("limit", DDMcpFunctionParameter.Type.INTEGER, null, false, "100", List.of("10", "100")),
            new DDMcpFunctionParameter("ratio", DDMcpFunctionParameter.Type.NUMBER, null, false, "0.5", null),
            new DDMcpFunctionParameter("readOnly", DDMcpFunctionParameter.Type.BOOLEAN, null, false, "true", null)
        ));
        Assertions.assertEquals(descriptor, gson.fromJson(gson.toJson(descriptor), DDMcpFunctionDescriptor.class));
    }

    @Test
    void preservesNumericArgumentsWithoutDoubleConversion() {
        var arguments = JsonParser.parseString("{\"id\":9007199254740993,\"amount\":0.1234567890123456789,\"value\":null}")
            .getAsJsonObject();
        var call = new DDMcpFunctionCall("database_query", arguments);
        var decoded = gson.fromJson(gson.toJson(call), DDMcpFunctionCall.class);
        Assertions.assertEquals("9007199254740993", decoded.arguments().get("id").getAsString());
        Assertions.assertEquals("0.1234567890123456789", decoded.arguments().get("amount").getAsString());
        Assertions.assertTrue(decoded.arguments().get("value").isJsonNull());
    }

    @Test
    void preservesExecutionFailureAndStructuredResult() {
        var result = new DDMcpFunctionResult(JsonParser.parseString("{\"message\":\"Permission denied\",\"rows\":[]}"), true);
        Assertions.assertEquals(result, gson.fromJson(gson.toJson(result), DDMcpFunctionResult.class));
    }
}
