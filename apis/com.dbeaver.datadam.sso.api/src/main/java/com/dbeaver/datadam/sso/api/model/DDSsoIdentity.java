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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Instant;

/**
 * Identity confirmed by a trusted application backend. The subject is scoped to the identity source.
 * Authentication time is the original login time, not the token issue time. MFA proof is absent until
 * a local second factor succeeds; older SSO identities without the proof remain readable.
 */
public record DDSsoIdentity(
    @NotNull String subject,
    @NotNull String email,
    @NotNull String source,
    @NotNull Instant authTime,
    @Nullable Instant mfaVerifiedAt
) {
    public DDSsoIdentity(String subject, String email, String source, Instant authTime) {
        this(subject, email, source, authTime, null);
    }
}
