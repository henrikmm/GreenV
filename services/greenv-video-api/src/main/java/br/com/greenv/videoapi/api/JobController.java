package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.service.JobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        var job = jobService.create(request);
        String baseUrl = baseUrl();
        JobResponse response = JobResponse.from(job, baseUrl);
        return ResponseEntity.created(URI.create(response.statusUrl())).body(response);
    }

    @PutMapping(path = "/{jobId}/source", consumes = MediaType.ALL_VALUE)
    ResponseEntity<Void> upload(@PathVariable UUID jobId, HttpServletRequest request) throws IOException {
        jobService.upload(jobId, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/complete")
    ResponseEntity<JobResponse> complete(@PathVariable UUID jobId) {
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(JobResponse.from(jobService.complete(jobId), baseUrl()));
    }

    @GetMapping("/{jobId}")
    JobResponse get(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.get(jobId), baseUrl());
    }

    @GetMapping(path = "/{jobId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] manifest(@PathVariable UUID jobId) {
        return jobService.manifest(jobId);
    }

    @PostMapping("/{jobId}/save")
    JobResponse save(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.save(jobId), baseUrl());
    }

    @DeleteMapping("/{jobId}")
    ResponseEntity<Void> delete(@PathVariable UUID jobId) {
        jobService.delete(jobId);
        return ResponseEntity.noContent().build();
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
