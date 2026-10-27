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
package com.dbeaver.datadam.mcp.api.service;

import com.dbeaver.datadam.mcp.api.exception.DDMcpFunctionException;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionCall;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionDescriptor;
import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionResult;
import org.jkiss.code.NotNull;

import java.util.List;

/**
 * Remotely available MCP functions of one authenticated desktop.
 */
public interface DDMcpFunctionService {
    String SERVICE_ID = "dbeaver.mcp.functions";

    @NotNull
    List<DDMcpFunctionDescriptor> getFunctions() throws DDMcpFunctionException;

    @NotNull
    DDMcpFunctionResult callFunction(@NotNull DDMcpFunctionCall call) throws DDMcpFunctionException;
}
