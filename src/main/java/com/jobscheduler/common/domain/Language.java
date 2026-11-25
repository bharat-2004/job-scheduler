package com.jobscheduler.common.domain;

/** Script languages. Adding one means one enum value plus one LanguageRuntime in the worker. */
public enum Language {
    PYTHON("py");

    private final String fileExtension;

    Language(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public String fileExtension() {
        return fileExtension;
    }
}
