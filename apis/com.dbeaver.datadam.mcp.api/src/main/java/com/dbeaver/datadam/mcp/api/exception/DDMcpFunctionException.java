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
package com.dbeaver.datadam.mcp.api.exception;

import com.dbeaver.datadam.mcp.api.model.DDMcpFunctionErrorCode;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

public class DDMcpFunctionException extends Exception {
    private final DDMcpFunctionErrorCode code;
    @Nullable
    private final JsonObject data;

    public DDMcpFunctionException(@NotNull DDMcpFunctionErrorCode code, @NotNull String message, @Nullable JsonObject data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    @NotNull
    public DDMcpFunctionErrorCode getCode() {
        return code;
    }

    @Nullable
    public JsonObject getData() {
        return data;
    }
}
