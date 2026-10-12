package io.flowforge.controlplane.job.persistence;

import io.flowforge.controlplane.job.domain.Job;
import io.flowforge.controlplane.job.domain.JobStatus;
import io.flowforge.controlplane.security.JobOwner;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class JobSpecifications {

    private JobSpecifications() {
    }

    public static Specification<Job> matching(
            JobStatus status,
            String jobType,
            JobOwner owner,
            boolean administrator) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (jobType != null) {
                predicates.add(builder.equal(root.get("jobType"), jobType));
            }
            if (!administrator) {
                predicates.add(builder.equal(root.get("ownerIssuer"), owner.issuer()));
                predicates.add(builder.equal(root.get("ownerSubject"), owner.subject()));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
