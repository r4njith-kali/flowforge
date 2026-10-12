package io.flowforge.controlplane.job.api;

import io.flowforge.controlplane.job.domain.Job;
import org.springframework.data.domain.Page;

import java.util.List;

public record JobPageResponse(
        List<JobResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static JobPageResponse from(Page<Job> jobs) {
        return new JobPageResponse(
                jobs.getContent().stream().map(JobResponse::from).toList(),
                jobs.getNumber(),
                jobs.getSize(),
                jobs.getTotalElements(),
                jobs.getTotalPages(),
                jobs.isFirst(),
                jobs.isLast());
    }
}
