package io.flowforge.controlplane.job.service;

import io.flowforge.controlplane.job.domain.InvalidJobStateException;
import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobNotFoundException;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.job.persistence.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobService = new JobService(
                jobRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                jdbcTemplate);
    }

    @Test
    void createSetsPendingStatusAndRetryDefaults() {
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Job job = jobService.create("email.send", Map.of("to", "user@example.test"), null, null, null);

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

        Job job = jobService.create("report.generate", Map.of(), runAt, 3, 10);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(job.getScheduledAt()).isEqualTo(runAt);
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getRetryDelaySeconds()).isEqualTo(10);
    }

    @Test
    void cancelTransitionsPendingJobAndUpdatesCompletionTime() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW.minusSeconds(5));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        Job cancelled = jobService.cancel(job.getId());

        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isEqualTo(NOW);
        assertThat(cancelled.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void cancelIsIdempotentForAlreadyCancelledJob() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        job.cancel(NOW);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThat(jobService.cancel(job.getId()).getStatus()).isEqualTo(JobStatus.CANCELLED);

        verify(jobRepository, never()).save(any());
    }

    @Test
    void cancelRejectsRunningJobs() {
        Job job = Job.create("email.send", Map.of(), null, 2, 0, NOW);
        job.start(NOW);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobService.cancel(job.getId()))
                .isInstanceOf(InvalidJobStateException.class);
    }

    @Test
    void getRejectsUnknownJobs() {
        UUID id = UUID.randomUUID();
        when(jobRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.get(id))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void listDelegatesFiltersAndPaging() {
        Pageable pageable = PageRequest.of(2, 10, Sort.by("createdAt"));

        jobService.list(JobStatus.PENDING, "email.send", pageable);

        verify(jobRepository).findAll(org.mockito.ArgumentMatchers.<Specification<Job>>any(), eq(pageable));
    }
}
