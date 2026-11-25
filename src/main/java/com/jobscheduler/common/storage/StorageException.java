package com.jobscheduler.common.storage;

/** Object storage is unreachable or refused the request. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
