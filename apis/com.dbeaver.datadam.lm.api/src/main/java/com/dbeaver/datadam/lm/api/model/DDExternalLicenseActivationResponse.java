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
package com.dbeaver.datadam.lm.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Response to an external license activation request, including HTTP error responses.
 * Successful requests and duplicate event IDs have {@code success = true} and no error.
 * Clients should ignore unknown JSON fields and handle unknown error codes as failures.
 */
public record DDExternalLicenseActivationResponse(boolean success, @Nullable ErrorDetails error) {
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * The code is a stable, machine-readable identifier; the message is for display only.
     * Error codes are strings so future codes do not break deserialization in older clients.
     */
    public record ErrorDetails(@NotNull String code, @NotNull String message) {
    }

    @NotNull
    public static DDExternalLicenseActivationResponse accepted() {
        return new DDExternalLicenseActivationResponse(true, null);
    }

    @NotNull
    public static DDExternalLicenseActivationResponse rejected(@NotNull String code, @NotNull String message) {
        return new DDExternalLicenseActivationResponse(false, new ErrorDetails(code, message));
    }
}
