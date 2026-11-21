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

import com.dbeaver.datadam.mcp.api.model.DDMcpDatasource;
import com.dbeaver.datadam.mcp.api.model.DDMcpDatasourceInfo;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionCall;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionDescriptor;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionParameter;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionResult;
import com.dbeaver.datadam.mcp.api.model.DDMcpListDatasourcesResult;
import com.dbeaver.datadam.mcp.api.model.DDMcpListProjectsResult;
import com.dbeaver.datadam.mcp.api.model.DDMcpProject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.ToNumberPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DDMcpFunctionContractTest {
    private final Gson gson = new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create();

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
    void readsLegacyDescriptorsWithoutAnnotations() {
        var descriptor = gson.fromJson("""
            {"id":"database_query","name":"Query","parameters":[]}
            """, DDMcpFunctionDescriptor.class);
        Assertions.assertEquals(new DDMcpFunctionDescriptor("database_query", "Query", null, List.of()), descriptor);
        Assertions.assertNull(descriptor.readOnlyHint());
        Assertions.assertNull(descriptor.destructiveHint());
        Assertions.assertNull(descriptor.idempotentHint());
    }

    @Test
    void preservesExplicitFalseAndPartialAnnotations() {
        var descriptor = new DDMcpFunctionDescriptor("database_query", "Query", null, List.of(), true, false, true);
        var json = gson.toJsonTree(descriptor).getAsJsonObject();
        Assertions.assertTrue(json.get("readOnlyHint").getAsBoolean());
        Assertions.assertFalse(json.get("destructiveHint").getAsBoolean());
        Assertions.assertTrue(json.get("idempotentHint").getAsBoolean());
        Assertions.assertEquals(descriptor, gson.fromJson(json, DDMcpFunctionDescriptor.class));

        var partial = gson.fromJson("""
            {"id":"database_query","name":"Query","parameters":[],"destructiveHint":false}
            """, DDMcpFunctionDescriptor.class);
        Assertions.assertNull(partial.readOnlyHint());
        Assertions.assertEquals(Boolean.FALSE, partial.destructiveHint());
        Assertions.assertNull(partial.idempotentHint());
    }

    @Test
    void preservesMapArgumentsWithoutLosingNullsOrNumericPrecision() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("id", new BigInteger("9007199254740993"));
        arguments.put("amount", new BigDecimal("0.1234567890123456789"));
        arguments.put("value", null);
        arguments.put("nested", Map.of("items", Arrays.asList(null, true, "text", new BigInteger("9223372036854775808"))));
        var call = new DDMcpFunctionCall("database_query", arguments);
        var decoded = gson.fromJson(gson.toJson(call), DDMcpFunctionCall.class);
        Assertions.assertEquals(call.functionId(), decoded.functionId());
        Assertions.assertEquals(new BigDecimal("9007199254740993"), decoded.arguments().get("id"));
        Assertions.assertEquals(new BigDecimal("0.1234567890123456789"), decoded.arguments().get("amount"));
        Assertions.assertTrue(decoded.arguments().containsKey("value"));
        Assertions.assertNull(decoded.arguments().get("value"));
        Map<?, ?> nested = Assertions.assertInstanceOf(Map.class, decoded.arguments().get("nested"));
        Assertions.assertEquals(Arrays.asList(null, true, "text", new BigDecimal("9223372036854775808")), nested.get("items"));
    }

    @Test
    void preservesExecutionFailureAndStructuredResult() {
        var result = new DDMcpFunctionResult(Map.of("message", "Permission denied", "rows", List.of()), true);
        Assertions.assertEquals(result, gson.fromJson(gson.toJson(result), DDMcpFunctionResult.class));
    }

    @Test
    void serializesPojoResultsWithoutChangingTheWireFormat() {
        var result = new DDMcpFunctionResult(new DDMcpProject("project-1", "Project", null), false);
        var expected = JsonParser.parseString("""
            {"value":{"id":"project-1","name":"Project","description":null},"error":false}
            """);
        Assertions.assertEquals(expected, gson.toJsonTree(result));
        var decoded = gson.fromJson(gson.toJson(result), DDMcpFunctionResult.class);
        Assertions.assertEquals(expected, gson.toJsonTree(decoded));
        Assertions.assertEquals(new DDMcpFunctionResult("text result", false),
            gson.fromJson("{\"value\":\"text result\",\"error\":false}", DDMcpFunctionResult.class));
    }

    @Test
    void preservesDatasourceInfoFieldsAndDisconnectedServerNulls() {
        var info = new DDMcpDatasourceInfo("connection-1", "Connection", "jdbc:sample://localhost/example",
            "Sample driver", "1.2", null, null, "Sample SQL", false, false, false);
        var expected = JsonParser.parseString("""
            {"dataSourceId":"connection-1","dataSourceName":"Connection","connectionURL":"jdbc:sample://localhost/example",
             "driverName":"Sample driver","driverVersion":"1.2","databaseServerName":null,"databaseServerVersion":null,
             "sqlDialectName":"Sample SQL","supportsCatalogs":false,"supportsSchemas":false,"isConnected":false}
            """);
        Assertions.assertEquals(expected, gson.toJsonTree(info));
        Assertions.assertEquals("dataSourceId", gson.toJsonTree(info).getAsJsonObject().keySet().iterator().next());
        Assertions.assertEquals(info, gson.fromJson(gson.toJson(info), DDMcpDatasourceInfo.class));
        var result = gson.toJsonTree(new DDMcpFunctionResult(info, false)).getAsJsonObject();
        Assertions.assertEquals(expected, result.getAsJsonObject("value"));
    }

    @Test
    void preservesDiscoveryResultFieldNamesAndTypedRoundTrips() {
        var projects = new DDMcpListProjectsResult(List.of(new DDMcpProject("project-1", "Project", null)));
        Assertions.assertEquals(JsonParser.parseString("""
            {"projects":[{"id":"project-1","name":"Project","description":null}]}
            """), gson.toJsonTree(projects));
        Assertions.assertEquals(projects, gson.fromJson(gson.toJson(projects), DDMcpListProjectsResult.class));

        var datasources = new DDMcpListDatasourcesResult(List.of(
            new DDMcpDatasource("connection-1", "Connection", "project-1", "Project", "PostgreSQL", true)));
        Assertions.assertEquals(JsonParser.parseString("""
            {"datasources":[{"id":"connection-1","name":"Connection","project_id":"project-1",
            "project_name":"Project","database_type":"PostgreSQL","requires_manual_connection":true}]}
            """), gson.toJsonTree(datasources));
        Assertions.assertEquals(datasources, gson.fromJson(gson.toJson(datasources), DDMcpListDatasourcesResult.class));
    }
}
