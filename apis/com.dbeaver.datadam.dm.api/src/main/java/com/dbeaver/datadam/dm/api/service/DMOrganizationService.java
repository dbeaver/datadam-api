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
package com.dbeaver.datadam.dm.api.service;

import com.dbeaver.datadam.dm.api.model.DMAuthInfo;
import com.dbeaver.datadam.dm.api.model.DMException;
import com.dbeaver.datadam.dm.api.model.DMOrganizationInfo;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Organization service.
 * Authenticates in application and provides operations with current organization.
 */
public interface DMOrganizationService {

    @NotNull
    default DMAuthInfo auth(
        @NotNull String encoded,
        @NotNull String applicationId,
        @NotNull String productId,
        @NotNull String productVersion
    ) {
        return auth(encoded, applicationId, productId, productVersion, null);
    }

    @NotNull
    DMAuthInfo auth(
        @NotNull String encoded,
        @NotNull String applicationId,
        @NotNull String productId,
        @NotNull String productVersion,
        @Nullable String identityToken
    );

    /**
     * Displays information about organization.
     */
    DMOrganizationInfo getOrganization();

    /**
     * Updates information about the organization (root domain).
     * @param rootDomain root domain of organization.
     */
    void updateOrganization(@NotNull String rootDomain) throws DMException;

}
