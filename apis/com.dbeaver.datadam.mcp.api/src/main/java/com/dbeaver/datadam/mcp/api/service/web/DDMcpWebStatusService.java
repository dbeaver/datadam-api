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
package com.dbeaver.datadam.mcp.api.service.web;

import com.dbeaver.datadam.mcp.api.exception.DDMcpStatusException;
import com.dbeaver.datadam.mcp.api.model.DDMcpStatus;
import org.jkiss.code.NotNull;

/**
 * Implemented by DataDam; desktop calls it to read or change the MCP status.
 */
public interface DDMcpWebStatusService {
    String SERVICE_ID = "datadam.mcp.status";

    @NotNull
    DDMcpStatus getStatus();

    void setStatus(@NotNull DDMcpStatus status) throws DDMcpStatusException;
}
