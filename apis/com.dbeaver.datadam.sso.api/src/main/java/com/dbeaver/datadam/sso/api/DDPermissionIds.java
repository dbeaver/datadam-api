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
package com.dbeaver.datadam.sso.api;

/** Account permission IDs shared by the server and API clients. Permission definitions and grants remain on the server. */
public final class DDPermissionIds {
    public static final String AUTHENTICATED = "AUTHENTICATED";
    public static final String ACCOUNT_ADMIN = "ACCOUNT_ADMIN";
    public static final String SETTINGS_MANAGE = "SETTINGS_MANAGE";
    public static final String ACCOUNT_READ = "ACCOUNT_READ";
    public static final String REMOTE_DAM_MANAGE = "REMOTE_DAM_MANAGE";
    public static final String PROFILE_READ = "PROFILE_READ";
    public static final String PROFILE_MANAGE = "PROFILE_MANAGE";
    public static final String OAUTH_CLIENT_MANAGE = "OAUTH_CLIENT_MANAGE";
    public static final String BILLING_READ = "BILLING_READ";
    public static final String BILLING_MANAGE = "BILLING_MANAGE";
    public static final String AI_KEY_READ = "AI_KEY_READ";
    public static final String AI_KEY_MANAGE = "AI_KEY_MANAGE";
    public static final String STORAGE_KEY_READ = "STORAGE_KEY_READ";
    public static final String STORAGE_KEY_MANAGE = "STORAGE_KEY_MANAGE";
    public static final String PROJECT_READ = "PROJECT_READ";
    public static final String PROJECT_MANAGE = "PROJECT_MANAGE";
    public static final String CONFIGURATION_READ = "CONFIGURATION_READ";
    public static final String CONFIGURATION_MANAGE = "CONFIGURATION_MANAGE";
    public static final String LICENSE_READ = "LICENSE_READ";
    public static final String LICENSE_DETAILS = "LICENSE_DETAILS";
    public static final String LICENSE_MANAGE = "LICENSE_MANAGE";
    public static final String MFA_READ = "MFA_READ";
    public static final String MFA_MANAGE = "MFA_MANAGE";
    public static final String SUPPORT_READ = "SUPPORT_READ";
    public static final String SUPPORT_MANAGE = "SUPPORT_MANAGE";
    public static final String DBEAVER_ADMIN = "DBEAVER_ADMIN";
    public static final String DBEAVER_LICENSE_ADMIN = "DBEAVER_LICENSE_ADMIN";
    public static final String DBEAVER_USER_DELETE = "DBEAVER_USER_DELETE";

    private DDPermissionIds() {
    }
}
