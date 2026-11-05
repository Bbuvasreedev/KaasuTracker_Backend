package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.AdditionalFund;
import com.kodegladiators.kaasutracker.model.FundStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdditionalFundRepository extends JpaRepository<AdditionalFund, Long> {

    List<AdditionalFund> findAllByUserIdOrderByFundDateDesc(String userId);

    List<AdditionalFund> findAllByUserIdAndFundDateBetweenAndStatus(
            String userId, LocalDate start, LocalDate end, FundStatus status
    );

    Optional<AdditionalFund> findByIdAndUserId(Long id, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fund from AdditionalFund fund where fund.id = :id and fund.user.id = :userId")
    Optional<AdditionalFund> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") String userId);
}
