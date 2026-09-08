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

import com.dbeaver.datadam.share.api.model.DDProjectFile;
import org.jkiss.code.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class DDChecksumUtils {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String CONFIGURATION_CHECKSUM_VERSION = "dbeaver-project-configuration-v1";

    private DDChecksumUtils() {
    }

    /**
     * Calculates a SHA-256 checksum of the local file contents before DataDam encryption.
     */
    @NotNull
    public static String calculateLocalFileChecksum(@NotNull Path path) throws IOException {
        MessageDigest digest = createDigest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Calculates a SHA-256 checksum of Base64-encoded encrypted file contents.
     */
    @NotNull
    public static String calculateEncryptedFileChecksum(@NotNull String encryptedContents) {
        return HexFormat.of().formatHex(calculateDigest(decodeEncryptedContents(encryptedContents)));
    }

    /**
     * Calculates a deterministic SHA-256 checksum from all project file names and encrypted contents.
     * The result is independent of the input list order.
     */
    @NotNull
    public static String calculateConfigurationChecksum(@NotNull List<DDProjectFile> files) {
        MessageDigest digest = createDigest();
        updateDigest(digest, CONFIGURATION_CHECKSUM_VERSION.getBytes(StandardCharsets.UTF_8));

        List<DDProjectFile> sortedFiles = files.stream()
            .sorted(Comparator.comparing(DDProjectFile::fileName))
            .toList();
        String previousFileName = null;
        for (DDProjectFile file : sortedFiles) {
            if (file.fileName().equals(previousFileName)) {
                throw new IllegalArgumentException("Duplicate project file name: " + file.fileName());
            }
            previousFileName = file.fileName();

            byte[] fileChecksum = calculateDigest(decodeEncryptedContents(file.encryptedContents()));
            updateDigest(digest, file.fileName().getBytes(StandardCharsets.UTF_8));
            updateDigest(digest, fileChecksum);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(@NotNull MessageDigest digest, @NotNull byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    @NotNull
    private static byte[] decodeEncryptedContents(@NotNull String encryptedContents) {
        return Base64.getDecoder().decode(encryptedContents);
    }

    @NotNull
    private static byte[] calculateDigest(@NotNull byte[] value) {
        return createDigest().digest(value);
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
