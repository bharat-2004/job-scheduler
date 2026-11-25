package com.jobscheduler.api.dto;

import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Language;
import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.domain.RunStatus;
import com.jobscheduler.common.domain.Schedule;
import com.jobscheduler.common.domain.ScheduleType;
import com.jobscheduler.common.domain.TriggerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Request and response shapes. Entities never leave the service layer; these records are the API contract.
 * Bean Validation here checks format only (required, sizes); business rules live in the domain.
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    // ---- auth ----

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {   // bcrypt uses only the first 72 bytes
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record UserResponse(UUID id, String email, Instant createdAt) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    // ---- jobs ----

    /** Used for POST (create) and PUT (full replace). */
    public record JobRequest(
            @NotBlank @Size(max = 100) String name,
            Language language,
            @NotBlank @Size(max = 65_536, message = "script must be at most 64 KB") String script,
            @NotNull @Valid ScheduleRequest schedule,
            @NotNull @Positive Integer timeoutSeconds) {

        public Language languageOrDefault() {
            return language == null ? Language.PYTHON : language;
        }
    }

    /** CRON needs {@code cron}; ONE_TIME needs {@code runAt}. Timezone defaults to UTC. */
    public record ScheduleRequest(
            @NotNull ScheduleType type,
            @Size(max = 100) String cron,
            Instant runAt,
            @Size(max = 50) String timezone) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }

    public record ScheduleResponse(ScheduleType type, String cron, Instant runAt, String timezone) {
        static ScheduleResponse from(Schedule s) {
            return new ScheduleResponse(s.type(), s.cronExpression(), s.runAt(), s.zone().getId());
        }
    }

    public record LastRunSummary(UUID id, RunStatus status, TriggerType triggerType,
                                 Instant scheduledTime, Instant finishedAt) {
        public static LastRunSummary from(Run run) {
            return run == null ? null : new LastRunSummary(run.getId(), run.getStatus(), run.getTriggerType(),
                    run.getScheduledTime(), run.getFinishedAt());
        }
    }

    public record JobResponse(UUID id, String name, Language language, int scriptVersion,
                              ScheduleResponse schedule, long timeoutSeconds, boolean enabled,
                              Instant nextRunTime, Instant createdAt, Instant updatedAt, LastRunSummary lastRun) {
        public static JobResponse from(Job job, Run lastRun) {
            return new JobResponse(job.getId(), job.getName(), job.getLanguage(), job.getScriptVersion(),
                    ScheduleResponse.from(job.getSchedule()), job.timeout().toSeconds(), job.isEnabled(),
                    job.getNextRunTime(), job.getCreatedAt(), job.getUpdatedAt(), LastRunSummary.from(lastRun));
        }
    }

    // ---- runs ----

    public record RunResponse(UUID id, UUID jobId, TriggerType triggerType, RunStatus status,
                              Instant scheduledTime, Instant dispatchedAt, Instant startedAt, Instant finishedAt,
                              int scriptVersion, long timeoutSeconds, Integer exitCode,
                              FailureReason failureReason, String failureDetail, boolean hasLogs, String workerId) {
        public static RunResponse from(Run r) {
            return new RunResponse(r.getId(), r.getJobId(), r.getTriggerType(), r.getStatus(), r.getScheduledTime(),
                    r.getDispatchedAt(), r.getStartedAt(), r.getFinishedAt(), r.getScriptVersion(),
                    r.timeout().toSeconds(), r.getExitCode(), r.getFailureReason(), r.getFailureDetail(),
                    r.getLogPath() != null, r.getWorkerId());
        }
    }

    // ---- paging ----

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
