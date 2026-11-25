package com.jobscheduler.common.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Run logs at {@code logs/{jobId}/{runId}.log}, uploaded once when the run finishes. */
public interface LogStorage {

    /** Uploads the file and returns the object path to store on the run. */
    String upload(UUID jobId, UUID runId, Path logFile);

    /** Opens a stored log for reading; empty if the object does not exist (e.g. expired). */
    Optional<InputStream> open(String logPath);

    static String path(UUID jobId, UUID runId) {
        return "logs/" + jobId + "/" + runId + ".log";
    }
}
