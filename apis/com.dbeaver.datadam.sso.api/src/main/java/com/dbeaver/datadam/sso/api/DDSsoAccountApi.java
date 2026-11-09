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

import com.dbeaver.datadam.sso.api.exception.DDSsoClientException;
import com.dbeaver.datadam.sso.api.model.DDSsoAccessContextResponse;
import org.jkiss.code.NotNull;
import org.jkiss.utils.HttpConstants;

/** Account's backend-only API. Service authentication is supplied by the transport. */
public interface DDSsoAccountApi {
    /** Resolves current account context and effective permissions using the Authorization header value, including the Bearer prefix. */
    @NotNull
    DDSsoAccessContextResponse getAccessContext(@NotNull String authorization) throws DDSsoClientException;

    /** Convenience for callers holding a raw access token, without the Bearer prefix. */
    @NotNull
    default DDSsoAccessContextResponse exchange(@NotNull String accessToken) throws DDSsoClientException {
        return getAccessContext(HttpConstants.BEARER_PREFIX + accessToken);
    }
}
