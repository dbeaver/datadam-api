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
 * Reports a successful external license activation. Reuse {@code eventId} on retries.
 * All fields are validated by the server; {@code externalLicenseId} may be absent for
 * {@code trial} licenses and is required for {@code purchased} licenses.
 * {@code product} identifies the external product (for example, a driver), with version
 * {@code externalProductVersion}. {@code internalProduct} and {@code internalProductVersion}
 * identify the DBeaver application that performed the activation.
 * {@code lmLicenseId} optionally identifies that application's LM license.
 * {@code activatedAt} is an ISO-8601 timestamp with an explicit timezone offset.
 */
public record DDExternalLicenseActivation(
    @NotNull String eventId,
    @NotNull String provider,
    @NotNull String email,
    @Nullable String externalLicenseId,
    @NotNull String externalProductVersion,
    @NotNull String licenseType,
    @NotNull String product,
    @NotNull String internalProduct,
    @NotNull String internalProductVersion,
    @Nullable String lmLicenseId,
    @NotNull String activatedAt
) {
}
