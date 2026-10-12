package io.flowforge.controlplane.idempotency.persistence;

import io.flowforge.controlplane.idempotency.domain.IdempotencyRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Id> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select record
            from IdempotencyRecord record
            where record.id.operationName = :operationName
              and record.id.key = :key
            """)
    Optional<IdempotencyRecord> findForUpdate(
            @Param("operationName") String operationName,
            @Param("key") String key);

    @Modifying
    @Query("delete from IdempotencyRecord record where record.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
