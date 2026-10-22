package io.flowforge.controlplane.job.service;

import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobNotFoundException;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.persistence.JobRepository;
import io.flowforge.controlplane.job.persistence.JobAttemptRepository;
import io.flowforge.controlplane.security.JobOwner;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.data.jpa.domain.Specification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final JobOwner OWNER = new JobOwner("https://issuer.test", "owner-1");

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAttemptRepository jobAttemptRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private EntityManager entityManager;

    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobService = new JobService(
                jobRepository,
                jobAttemptRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                jdbcTemplate,
                entityManager);
    }

    @Test
    void createSetsPendingStatusAndRetryDefaults() {
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job job = jobService.create(
                "email.send", Map.of("to", "user@example.test"), null, null, null, OWNER);

        assertThat(job.getId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getCreatedAt()).isEqualTo(NOW);
        assertThat(job.getMaxAttempts()).isEqualTo(Job.DEFAULT_MAX_ATTEMPTS);
        assertThat(job.getRetryDelaySeconds()).isZero();
    }

    @Test
    void createSchedulesJobsDatedInTheFuture() {
        Instant runAt = NOW.plusSeconds(60);
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job job = jobService.create("report.generate", Map.of(), runAt, 3, 10, OWNER);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getScheduledAt()).isEqualTo(runAt);
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getRetryDelaySeconds()).isEqualTo(10);
    }

    @Test
    void cancelTransitionsPendingJobAndUpdatesCompletionTime() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW.minusSeconds(5));
        Job cancelledJob = Job.create("email.send", Map.of(), null, 2, 0, NOW.minusSeconds(5));
        cancelledJob.cancel(NOW);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job), Optional.of(cancelledJob));
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenReturn(java.util.List.of("CANCELLED"));

        Job cancelled = jobService.cancel(job.getId(), OWNER, true);

        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isEqualTo(NOW);
        assertThat(cancelled.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void cancelIsIdempotentForAlreadyCancelledJob() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        job.cancel(NOW);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThat(jobService.cancel(job.getId(), OWNER, true).getStatus()).isEqualTo(JobStatus.CANCELLED);

        verify(jobRepository, never()).save(any());
    }

    @Test
    void runningJobReceivesCancellationRequest() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        job.start(NOW);
        Job cancelRequested = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        cancelRequested.start(NOW);
        cancelRequested.requestCancellation(NOW);
        when(jobRepository.findById(job.getId()))
                .thenReturn(Optional.of(job), Optional.of(cancelRequested));
        when(jdbcTemplate.query(
                any(String.class),
                org.mockito.ArgumentMatchers.<RowMapper<String>>any(),
                any(Object[].class))).thenReturn(java.util.List.of("RUNNING"));

        Job result = jobService.cancel(job.getId(), OWNER, true);

        assertThat(result.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(result.getCancelRequestedAt()).isEqualTo(NOW);
    }

    @Test
    void getRejectsUnknownJobs() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.get(id, OWNER, true))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void nonAdministratorCannotReadAnotherUsersJob() {
        UUID id = UUID.randomUUID();
        Job someoneElsesJob = Job.create(
                "email.send",
                Map.of(),
                null,
                2,
                0,
                NOW,
                new JobOwner("https://issuer.test", "another-user"));
        when(jobRepository.findById(id)).thenReturn(Optional.of(someoneElsesJob));

        assertThatThrownBy(() -> jobService.get(id, OWNER, false))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void nonAdministratorCannotReadAnotherUsersAttempts() {
        UUID id = UUID.randomUUID();
        Job someoneElsesJob = Job.create(
                "email.send",
                Map.of(),
                null,
                2,
                0,
                NOW,
                new JobOwner("https://issuer.test", "another-user"));
        when(jobRepository.findById(id)).thenReturn(Optional.of(someoneElsesJob));

        assertThatThrownBy(() -> jobService.listAttempts(id, OWNER, false))
                .isInstanceOf(JobNotFoundException.class);

        verify(jobAttemptRepository, never()).findByJobIdOrderByAttemptNumber(id);
    }

    @Test
    void administratorCanReadHistoricalJobWithoutOwner() {
        UUID id = UUID.randomUUID();
        Job historicalJob = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        when(jobRepository.findById(id)).thenReturn(Optional.of(historicalJob));

        assertThat(jobService.get(id, OWNER, true)).isSameAs(historicalJob);
    }

    @Test
    void listDelegatesFiltersAndPaging() {
        Pageable pageable = PageRequest.of(2, 10, Sort.by("createdAt"));

        jobService.list(JobStatus.PENDING, "email.send", pageable, OWNER, true);

        verify(jobRepository).findAll(org.mockito.ArgumentMatchers.<Specification<Job>>any(), eq(pageable));
    }
}
