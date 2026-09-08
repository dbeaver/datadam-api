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
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DDFingerprintUtilsTest {
    private static final UUID PROJECT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    @Test
    void calculateFileFingerprintUsesStableFormat() {
        assertEquals(
            "sha256-v1:591a94eb225bbdd480f81ba408cfb182e95f495e197ace6933d2e59a72cf6d6a",
            DDFingerprintUtils.calculateFileFingerprint(
                PROJECT_ID,
                "data-sources.json",
                bytes("{\"connections\":[]}")
            )
        );
    }

    @Test
    void calculateFileFingerprintIncludesProjectIdFileNameAndContents() {
        String fingerprint = DDFingerprintUtils.calculateFileFingerprint(PROJECT_ID, "file.json", bytes("content"));

        assertNotEquals(
            fingerprint,
            DDFingerprintUtils.calculateFileFingerprint(OTHER_PROJECT_ID, "file.json", bytes("content"))
        );
        assertNotEquals(
            fingerprint,
            DDFingerprintUtils.calculateFileFingerprint(PROJECT_ID, "renamed.json", bytes("content"))
        );
        assertNotEquals(
            fingerprint,
            DDFingerprintUtils.calculateFileFingerprint(PROJECT_ID, "file.json", bytes("changed"))
        );
    }

    @Test
    void calculateRevisionUsesStableFormat() {
        DDProjectFile file = creaetFile(PROJECT_ID, "data-sources.json", "{\"connections\":[]}", "encrypted");

        assertEquals(
            "sha256-v1:4c8a220e9cc7b0114612480fa2cee747ee28392f980d8730cb7f9b62837472df",
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(file)).fingerprint()
        );
    }

    @Test
    void calculateRevisionIsIndependentOfFileAndHexCaseOrder() {
        DDProjectFile first = creaetFile(PROJECT_ID, "first.json", "first", "encrypted-first");
        DDProjectFile second = creaetFile(PROJECT_ID, "second.json", "second", "encrypted-second");
        DDProjectFile uppercaseFingerprint = new DDProjectFile(
            first.fileName(),
            "different-encrypted-content",
            "sha256-v1:" + first.fingerprint().substring("sha256-v1:".length()).toUpperCase()
        );

        assertEquals(
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(first, second)),
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(second, uppercaseFingerprint))
        );
    }

    @Test
    void calculateRevisionIncludesProjectAndFileFingerprints() {
        DDProjectFile file = creaetFile(PROJECT_ID, "file.json", "content", "encrypted");
        DDProjectFile changed = creaetFile(PROJECT_ID, "file.json", "changed", "encrypted");

        assertNotEquals(
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(file)),
            DDFingerprintUtils.calculateRevision(OTHER_PROJECT_ID, List.of(file))
        );
        assertNotEquals(
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(file)),
            DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(changed))
        );
    }

    @Test
    void calculateRevisionRejectsDuplicateFileNames() {
        DDProjectFile first = creaetFile(PROJECT_ID, "file.json", "first", "encrypted-first");
        DDProjectFile second = creaetFile(PROJECT_ID, "file.json", "second", "encrypted-second");

        assertThrows(
            IllegalArgumentException.class,
            () -> DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(first, second))
        );
    }

    @Test
    void calculateRevisionRejectsInvalidFileFingerprint() {
        DDProjectFile file = new DDProjectFile("file.json", "encrypted", "invalid");

        assertThrows(
            IllegalArgumentException.class,
            () -> DDFingerprintUtils.calculateRevision(PROJECT_ID, List.of(file))
        );
    }

    private static DDProjectFile creaetFile(
        @NotNull UUID projectId,
        @NotNull String fileName,
        @NotNull String contents,
        @NotNull String encryptedContents
    ) {
        return new DDProjectFile(
            fileName,
            encryptedContents,
            DDFingerprintUtils.calculateFileFingerprint(projectId, fileName, bytes(contents))
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
