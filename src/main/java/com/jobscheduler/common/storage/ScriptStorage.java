package com.jobscheduler.common.storage;

import com.jobscheduler.common.domain.Language;

import java.util.UUID;

/**
 * Immutable, versioned scripts at {@code scripts/{jobId}/v{n}.{ext}}. A version is never
 * overwritten, so a run that snapshotted version n always executes exactly that code.
 */
public interface ScriptStorage {

    void upload(UUID jobId, int version, Language language, byte[] content);

    byte[] fetch(UUID jobId, int version, Language language);

    static String path(UUID jobId, int version, Language language) {
        return "scripts/" + jobId + "/v" + version + "." + language.fileExtension();
    }
}
