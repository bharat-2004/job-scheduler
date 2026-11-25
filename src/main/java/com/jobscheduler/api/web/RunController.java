package com.jobscheduler.api.web;

import com.jobscheduler.api.RunService;
import com.jobscheduler.api.dto.ApiDtos.RunResponse;
import com.jobscheduler.api.security.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.UUID;

/** Runs have a top-level path because a run id is unique on its own. */
@RestController
@RequestMapping("/api/v1/runs")
@Profile("api")
public class RunController {

    private final RunService runService;
    private final CurrentUser currentUser;

    public RunController(RunService runService, CurrentUser currentUser) {
        this.runService = runService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable UUID runId) {
        return runService.get(currentUser.id(), runId);
    }

    /** Streams the stored log straight from object storage; never loads it all into memory. */
    @GetMapping("/{runId}/logs")
    public ResponseEntity<StreamingResponseBody> logs(@PathVariable UUID runId) {
        InputStream log = runService.openLog(currentUser.id(), runId);
        StreamingResponseBody body = out -> {
            try (log) {
                log.transferTo(out);
            }
        };
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }
}
