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
package com.dbeaver.messaging.rpc;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.lang.reflect.Type;

/** Encodes and decodes declared RPC types using the payload representation accepted by the endpoint transport. */
public interface MessagingRpcCodec<P> {
    @Nullable
    P encode(@Nullable Object value, @NotNull Type type) throws Exception;

    @Nullable
    Object decode(@Nullable P payload, @NotNull Type type) throws Exception;
}
