package com.jobscheduler.worker.runtime;

import com.jobscheduler.common.domain.Language;

import java.util.List;

/**
 * Strategy: how to run a script of one language. Supporting a new language is one new
 * implementation (plus the enum value); the execution code does not change.
 */
public interface LanguageRuntime {

    Language language();

    /** Container image with the interpreter. */
    String image();

    /** File name the script gets inside the container's /job directory. */
    String scriptFileName();

    /** Command that runs the script at the given in-container path. */
    List<String> command(String scriptPath);
}
