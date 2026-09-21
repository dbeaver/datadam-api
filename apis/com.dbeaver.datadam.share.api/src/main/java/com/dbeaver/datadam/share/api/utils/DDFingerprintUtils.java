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
package com.dbeaver.datadam.share.api.utils;

import com.dbeaver.datadam.share.api.model.DDSharedProjectFile;
import org.jkiss.code.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class DDFingerprintUtils {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String FINGERPRINT_PREFIX = "sha256-v1:";
    private static final String FILE_FINGERPRINT_VERSION = "dbeaver-project-file-v1";
    private static final String CONFIGURATION_FINGERPRINT_VERSION = "dbeaver-project-revision-v1";

    private DDFingerprintUtils() {
    }

    /**
     * Calculates a project-scoped fingerprint from the file name and its contents before DataDam encryption.
     */
    @NotNull
    public static String calculateFileFingerprint(
        @NotNull UUID projectId,
        @NotNull String fileName,
        @NotNull byte[] contents
    ) {
        MessageDigest digest = createDigest();
        updateDigest(digest, FILE_FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8));
        updateDigest(digest, projectId.toString().getBytes(StandardCharsets.UTF_8));
        updateDigest(digest, fileName.getBytes(StandardCharsets.UTF_8));
        updateDigest(digest, contents);
        return formatFingerprint(digest.digest());
    }

    /**
     * Calculates a project-scoped configuration fingerprint from client-generated file fingerprints.
     * The result is independent of the input list order.
     */
    @NotNull
    public static String calculateConfigurationFingerprint(
        @NotNull UUID projectId,
        @NotNull List<DDSharedProjectFile> files
    ) {
        MessageDigest digest = createDigest();
        updateDigest(digest, CONFIGURATION_FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8));
        updateDigest(digest, projectId.toString().getBytes(StandardCharsets.UTF_8));

        List<DDSharedProjectFile> sortedFiles = files.stream()
            .sorted(Comparator.comparing(DDSharedProjectFile::fileName))
            .toList();
        String previousFileName = null;
        for (DDSharedProjectFile file : sortedFiles) {
            if (file.fileName().equals(previousFileName)) {
                throw new IllegalArgumentException("Duplicate project file name: " + file.fileName());
            }
            previousFileName = file.fileName();

            // Decode hex so equivalent textual representations contribute the same digest bytes.
            updateDigest(digest, parseFingerprint(file.fingerprint(), file.fileName()));
        }
        return formatFingerprint(digest.digest());
    }

    private static void updateDigest(@NotNull MessageDigest digest, @NotNull byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    @NotNull
    private static byte[] parseFingerprint(@NotNull String fingerprint, @NotNull String fileName) {
        if (!fingerprint.startsWith(FINGERPRINT_PREFIX)) {
            throw new IllegalArgumentException("Unsupported fingerprint for project file: " + fileName);
        }
        String encodedFingerprint = fingerprint.substring(FINGERPRINT_PREFIX.length());
        if (encodedFingerprint.length() != 64) {
            throw new IllegalArgumentException("Invalid fingerprint for project file: " + fileName);
        }
        try {
            return HexFormat.of().parseHex(encodedFingerprint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid fingerprint for project file: " + fileName, e);
        }
    }

    @NotNull
    private static String formatFingerprint(@NotNull byte[] value) {
        return FINGERPRINT_PREFIX + HexFormat.of().formatHex(value);
    }

    @NotNull
    private static MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available", e);
        }
    }
}
