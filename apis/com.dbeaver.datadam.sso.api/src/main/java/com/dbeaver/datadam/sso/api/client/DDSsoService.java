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
package com.dbeaver.datadam.sso.api.client;

import com.dbeaver.datadam.sso.api.DDSsoConstants;
import com.dbeaver.datadam.sso.api.exception.DDSsoClientException;
import com.dbeaver.datadam.sso.api.model.DDSsoDesktopApprovalRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoDesktopApprovalResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoIdentity;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenResponse;
import org.jkiss.code.NotNull;

import java.net.URI;

/** Coordinates SSO protocol responses, identity verification and browser destinations. */
public class DDSsoService {
    @NotNull
    private final DDSsoClientConfig properties;
    @NotNull
    private final DDSsoClient client;
    @NotNull
    private final DDSsoTokenVerifier verifier;

    public DDSsoService(@NotNull DDSsoClientConfig properties, @NotNull DDSsoClient client, @NotNull DDSsoTokenVerifier verifier) {
        this.properties = properties;
        this.client = client;
        this.verifier = verifier;
    }

    @NotNull
    public URI endpoint(@NotNull String path) {
        return client.endpoint(path);
    }

    @NotNull
    public DDSsoIdentity exchange(@NotNull String code, @NotNull String codeVerifier) throws DDSsoClientException {
        DDSsoTokenResponse token = client.exchange(new DDSsoTokenRequest(code, codeVerifier, properties.clientId(), properties.redirectUri()));
        if (token.idToken() == null || token.idToken().length() > DDSsoTokenVerifier.MAX_TOKEN_LENGTH || token.expiresIn() <= 0) {
            throw new DDSsoClientException("Invalid SSO token response");
        }
        return verifier.verifyIdentity(token.idToken());
    }

    @NotNull
    public String createHandoff(@NotNull DDSsoHandoffRequest request) throws DDSsoClientException {
        DDSsoHandoffResponse handoff = client.createHandoff(request);
        if (handoff.handoff() == null || !DDSsoConstants.BASE64URL_256_PATTERN.matcher(handoff.handoff()).matches()
            || handoff.expiresIn() <= 0) {
            throw new DDSsoClientException("Invalid SSO handoff response");
        }
        return endpoint(DDSsoConstants.HANDOFF_PATH) + "?" + DDSsoConstants.PARAM_HANDOFF + "=" + handoff.handoff();
    }

    @NotNull
    public String approveDesktop(@NotNull DDSsoDesktopApprovalRequest request) throws DDSsoClientException {
        DDSsoDesktopApprovalResponse approval = client.approveDesktop(request);
        if (approval.completion() == null || !DDSsoConstants.BASE64URL_256_PATTERN.matcher(approval.completion()).matches()) {
            throw new DDSsoClientException("Invalid SSO desktop approval response");
        }
        return endpoint(DDSsoConstants.DESKTOP_RESUME_PATH) + "?completion=" + approval.completion();
    }
}
