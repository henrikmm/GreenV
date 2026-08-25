package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.Retention;
import br.com.greenv.videoapi.storage.LocalJobStore;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TransientCleanupService {

    private final LocalJobStore jobStore;
    private final PipelineProperties properties;
    private final Clock clock;

    public TransientCleanupService(LocalJobStore jobStore, PipelineProperties properties, Clock clock) {
        this.jobStore = jobStore;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void removeExpiredJobs() {
        var cutoff = clock.instant().minus(properties.transientDays(), ChronoUnit.DAYS);
        for (var job : jobStore.listJobs()) {
            if (job.retention() == Retention.TRANSIENT && job.updatedAt().isBefore(cutoff)) {
                jobStore.deleteJob(job.jobId());
            }
        }
    }
}
