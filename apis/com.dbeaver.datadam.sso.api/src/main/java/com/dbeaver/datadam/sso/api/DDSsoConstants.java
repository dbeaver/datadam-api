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

import java.util.regex.Pattern;

/** Shared wire constants for SSO clients and server endpoints. */
public final class DDSsoConstants {
    public static final String AUTHORIZE_PATH = "/authorize";
    public static final String TOKEN_PATH = "/token";
    public static final String HANDOFF_PATH = "/handoff";
    public static final String LOGOUT_PATH = "/logout";
    public static final String JWKS_PATH = "/.well-known/jwks.json";
    public static final String HEALTH_PATH = "/health";

    public static final String PARAM_FLOW_ID = "flow_id";
    public static final String PARAM_HANDOFF = "handoff";
    public static final String PARAM_LOGOUT = "logout";
    public static final String PARAM_LOGOUT_CHALLENGE = "logout_challenge";
    public static final String PARAM_PROMPT = "prompt";
    public static final String PARAM_ERROR_URI = "error_uri";
    public static final String RESPONSE_EXPIRES_IN = "expires_in";
    public static final String PROMPT_LOGIN = "login";

    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_INVALID_CLIENT = "invalid_client";
    public static final String ERROR_INVALID_GRANT = "invalid_grant";
    public static final String ERROR_INVALID_HANDOFF = "invalid_handoff";
    public static final String ERROR_INVALID_LOGOUT = "invalid_logout";
    public static final String ERROR_UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String ERROR_LOGIN_REQUIRED = "login_required";
    public static final String ERROR_SERVER = "server_error";
    public static final String ERROR_TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_SOURCE = "source";
    public static final String CLAIM_AUTH_TIME = "auth_time";
    /** ISO-8601 instant string; full precision is needed to distinguish MFA enrollment changes within one second. */
    public static final String CLAIM_MFA_VERIFIED_AT = "mfa_verified_at";
    public static final String CLAIM_TOKEN_USE = "token_use";
    public static final String TOKEN_USE_IDENTITY = "identity";
    public static final String TOKEN_USE_SESSION = "sso_session";

    public static final String ALGORITHM_ED25519 = "Ed25519";
    public static final String ALGORITHM_SHA256 = "SHA-256";
    public static final String PKCE_METHOD_S256 = "S256";
    public static final int OPAQUE_VALUE_BYTES = 32;
    public static final int SHA256_BYTES = 32;
    public static final int ED25519_PUBLIC_KEY_BYTES = 32;
    public static final int ED25519_SIGNATURE_BYTES = 64;
    public static final Pattern BASE64URL_256_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    public static final String HEADER_REFERRER_POLICY = "Referrer-Policy";
    public static final String REFERRER_POLICY_NO_REFERRER = "no-referrer";

    private DDSsoConstants() {
    }
}
