package com.jobscheduler.api.web;

/** Exceptions the API layer raises; each maps to one HTTP status in {@link ApiExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404. Also used for another user's job, so job IDs cannot be probed. */
    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException() {
            super("job not found");
        }
    }

    /** 404. */
    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException() {
            super("run not found");
        }
    }

    /** 404: the run has no stored log (not finished yet, failed before starting, or expired). */
    public static class LogNotFoundException extends RuntimeException {
        public LogNotFoundException(String message) {
            super(message);
        }
    }

    /** 409: manual trigger while a run is RUNNING or a manual run is already QUEUED. */
    public static class JobAlreadyRunningException extends RuntimeException {
        public JobAlreadyRunningException() {
            super("job already has a run in progress or a manual run waiting");
        }
    }

    /** 409. */
    public static class DuplicateJobNameException extends RuntimeException {
        public DuplicateJobNameException(String name) {
            super("you already have a job named '" + name + "'");
        }
    }

    /** 409. */
    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException() {
            super("email is already registered");
        }
    }

    /** 401. Same message for unknown email and wrong password. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("invalid email or password");
        }
    }
}
