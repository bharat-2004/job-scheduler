package com.jobscheduler.api.web;

import com.jobscheduler.api.JobService;
import com.jobscheduler.api.RunService;
import com.jobscheduler.api.dto.ApiDtos.EnabledRequest;
import com.jobscheduler.api.dto.ApiDtos.JobRequest;
import com.jobscheduler.api.dto.ApiDtos.JobResponse;
import com.jobscheduler.api.dto.ApiDtos.PageResponse;
import com.jobscheduler.api.dto.ApiDtos.RunResponse;
import com.jobscheduler.api.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Profile("api")
public class JobController {

    private static final int MAX_PAGE_SIZE = 100;

    private final JobService jobService;
    private final RunService runService;
    private final CurrentUser currentUser;

    public JobController(JobService jobService, RunService runService, CurrentUser currentUser) {
        this.jobService = jobService;
        this.runService = runService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse create(@Valid @RequestBody JobRequest req) {
        return jobService.create(currentUser.id(), req);
    }

    @GetMapping
    public PageResponse<JobResponse> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return jobService.list(currentUser.id(), pageOf(page, size, Sort.by("createdAt").descending()));
    }

    @GetMapping("/{jobId}")
    public JobResponse get(@PathVariable UUID jobId) {
        return jobService.get(currentUser.id(), jobId);
    }

    @PutMapping("/{jobId}")
    public JobResponse update(@PathVariable UUID jobId, @Valid @RequestBody JobRequest req) {
        return jobService.update(currentUser.id(), jobId, req);
    }

    @PatchMapping("/{jobId}")
    public JobResponse setEnabled(@PathVariable UUID jobId, @Valid @RequestBody EnabledRequest req) {
        return jobService.setEnabled(currentUser.id(), jobId, req.enabled());
    }

    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID jobId) {
        jobService.delete(currentUser.id(), jobId);
    }

    /** Manual trigger, modelled as creating a run. */
    @PostMapping("/{jobId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunResponse trigger(@PathVariable UUID jobId) {
        return runService.triggerManual(currentUser.id(), jobId);
    }

    @GetMapping("/{jobId}/runs")
    public PageResponse<RunResponse> history(@PathVariable UUID jobId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return runService.history(currentUser.id(), jobId, pageOf(page, size, Sort.unsorted()));
    }

    static PageRequest pageOf(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }
}
