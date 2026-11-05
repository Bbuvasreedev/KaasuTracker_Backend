package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.EmiInstallment;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmiInstallmentRepository extends JpaRepository<EmiInstallment, Long> {

    Optional<EmiInstallment> findByEmiIdAndDueDate(Long emiId, LocalDate dueDate);

    List<EmiInstallment> findAllByEmiIdOrderByDueDateAsc(Long emiId);

    long countByEmiIdAndStatus(Long emiId, EmiInstallmentStatus status);

    @Query("""
            select new com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot(
                installment.emi.id, installment.dueDate, installment.amount, installment.status)
            from EmiInstallment installment
            where installment.emi.user.id = :userId
            """)
    List<EmiInstallmentSnapshot> findAllSnapshotsByUserId(@Param("userId") String userId);

    @Query("""
            select new com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot(
                installment.emi.id, installment.dueDate, installment.amount, installment.status)
            from EmiInstallment installment
            where installment.emi.user.id = :userId
              and installment.dueDate between :startDate and :endDate
            """)
    List<EmiInstallmentSnapshot> findSnapshotsByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            select new com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot(
                installment.emi.id, installment.dueDate, installment.amount, installment.status)
            from EmiInstallment installment
            where installment.emi.id = :emiId
              and installment.dueDate between :startDate and :endDate
            """)
    List<EmiInstallmentSnapshot> findSnapshotsByEmiAndDueDateBetween(
            @Param("emiId") Long emiId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    boolean existsByEmiIdAndDueDateAndStatus(
            Long emiId, LocalDate dueDate, EmiInstallmentStatus status);

    List<EmiInstallment> findAllByDueDateBetween(LocalDate startDate, LocalDate endDate);

    @Query("""
            select coalesce(sum(installment.amount), 0)
            from EmiInstallment installment
            where installment.dueDate between :startDate and :endDate
              and installment.emi.user.id = :userId
            """)
    BigDecimal sumScheduledForUserAndPeriod(
            @Param("userId") String userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            select coalesce(sum(installment.amount), 0)
            from EmiInstallment installment
            where installment.status = :status
              and installment.dueDate between :startDate and :endDate
              and installment.emi.user.id = :userId
            """)
    BigDecimal sumForUserAndPeriod(
            @Param("userId") String userId,
            @Param("status") EmiInstallmentStatus status,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
