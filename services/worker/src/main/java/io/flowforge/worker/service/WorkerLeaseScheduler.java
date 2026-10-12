package io.flowforge.worker.service;

import io.flowforge.worker.domain.JobExecutionClaim;
import io.flowforge.worker.persistence.JobExecutionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executor;

@Component
public class WorkerLeaseScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerLeaseScheduler.class);

    private final JobExecutionStore store;
    private final JobExecutionService executionService;
    private final WorkerIdentity workerIdentity;
    private final Executor executionExecutor;
    private final Duration lease;
    private final int maxAttempts;
    private final int recoveryBatchSize;

    public WorkerLeaseScheduler(
            JobExecutionStore store,
            JobExecutionService executionService,
            WorkerIdentity workerIdentity,
            @Qualifier("workerExecutionExecutor") Executor executionExecutor,
            @Value("${flowforge.worker.lease:PT1M}") Duration lease,
            @Value("${flowforge.worker.max-attempts:100}") int maxAttempts,
            @Value("${flowforge.worker.heartbeat-interval-ms:15000}") long heartbeatIntervalMs,
            @Value("${flowforge.worker.recovery-batch-size:10}") int recoveryBatchSize) {
        if (lease.isNegative()
                || lease.isZero()
                || heartbeatIntervalMs <= 0
                || heartbeatIntervalMs * 2 >= lease.toMillis()
                || maxAttempts < 1
                || recoveryBatchSize < 1) {
            throw new IllegalArgumentException("Invalid worker lease, heartbeat, or recovery configuration");
        }
        this.store = store;
        this.executionService = executionService;
        this.workerIdentity = workerIdentity;
        this.executionExecutor = executionExecutor;
        this.lease = lease;
        this.maxAttempts = maxAttempts;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    @Scheduled(fixedDelayString = "${flowforge.worker.heartbeat-interval-ms:15000}")
    public void renewLeases() {
        store.heartbeat(workerIdentity.value(), lease);
    }

    @Scheduled(fixedDelayString = "${flowforge.worker.recovery-interval-ms:5000}")
    public void recoverExpiredAndRetryableJobs() {
        var claims = store.claimRecoverable(
                workerIdentity.value(), recoveryBatchSize, lease, maxAttempts);
        if (claims.isEmpty()) {
            return;
        }
        LOGGER.atInfo().addKeyValue("claimedJobs", claims.size()).log("Claimed recoverable jobs");
        for (JobExecutionClaim claim : claims) {
            executionExecutor.execute(() -> executionService.execute(claim));
        }
    }
}
