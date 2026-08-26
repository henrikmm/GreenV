package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.Retention;
import br.com.greenv.videoapi.port.LegacyJobStore;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TransientCleanupService {

    private final LegacyJobStore jobStore;
    private final PipelineProperties pipelineProperties;
    private final Clock clock;

    public TransientCleanupService(
            LegacyJobStore jobStore,
            PipelineProperties pipelineProperties,
            Clock clock) {
        this.jobStore = jobStore;
        this.pipelineProperties = pipelineProperties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void removeExpiredJobs() {
        var cutoff = clock.instant().minus(pipelineProperties.transientDays(), ChronoUnit.DAYS);
        for (var job : jobStore.listJobs()) {
            if (job.retention() == Retention.TRANSIENT && job.updatedAt().isBefore(cutoff)) {
                jobStore.deleteJob(job.jobId());
            }
        }
    }
}
