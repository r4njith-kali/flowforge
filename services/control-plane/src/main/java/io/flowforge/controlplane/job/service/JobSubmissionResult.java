package io.flowforge.controlplane.job.service;

import io.flowforge.controlplane.job.domain.Job;

public record JobSubmissionResult(Job job, boolean replayed) {
}
