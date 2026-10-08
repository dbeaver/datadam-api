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
package com.dbeaver.datadam.sso.api.model;

import com.dbeaver.datadam.sso.api.DDSsoConstants;
import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;

/** Sent by an authenticated application backend after validating its local login and state. */
public record DDSsoHandoffRequest(
    @NotNull DDSsoIdentity identity,
    @NotNull DDSsoAuthorizeRequest authorization,
    @SerializedName(DDSsoConstants.PARAM_FLOW_ID) @NotNull String flowId
) {
}
