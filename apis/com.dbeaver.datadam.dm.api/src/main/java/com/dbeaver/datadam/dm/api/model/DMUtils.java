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
package com.dbeaver.datadam.dm.api.model;

import org.jkiss.code.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DMUtils {
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{2,6}");

    public static boolean isDomainAddress(final @NotNull String address) {
        if (!address.contains(".")) {
            return "localhost".equals(address);
        }
        Matcher matcher = DOMAIN_PATTERN.matcher(address);
        return matcher.matches();
    }

    @NotNull
    public static String getFullDomain(@NotNull String subdomain, @NotNull DMOrganizationInfo organizationInfo) {
        return subdomain + "." + organizationInfo.rootDomain();
    }
}
