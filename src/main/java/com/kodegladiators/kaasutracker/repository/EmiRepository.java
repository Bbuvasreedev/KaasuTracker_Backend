package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface EmiRepository extends JpaRepository<Emi, Long> {

    List<Emi> findAllByUserId(String userId);

    java.util.Optional<Emi> findByUserIdAndCreationKey(String userId, String creationKey);

    List<Emi> findAllByUserIdAndIsActiveTrue(String userId);

    List<Emi> findAllByIsActiveTrue();

    List<Emi> findAllByUserIdAndStatusOrderByIdAsc(String userId, EmiStatus status);

    List<Emi> findAllByStatusOrderByIdAsc(EmiStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select emi from Emi emi where emi.id = :id and emi.user.id = :userId")
    java.util.Optional<Emi> findByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") String userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select emi from Emi emi where emi.id = :id")
    java.util.Optional<Emi> findByIdForUpdate(@Param("id") Long id);

    @Query("select emi from Emi emi where emi.isActive = true and emi.dueDateDay = :dueDateDay")
    List<Emi> findAllActiveByDueDateDay(@Param("dueDateDay") Integer dueDateDay);
}
