package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.BorrowedFund;
import com.kodegladiators.kaasutracker.model.FundStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BorrowedFundRepository extends JpaRepository<BorrowedFund, Long> {
    List<BorrowedFund> findAllByUserIdOrderByFundDateDesc(String userId);

    List<BorrowedFund> findAllByUserIdAndFundDateBetweenAndStatus(
            String userId, LocalDate start, LocalDate end, FundStatus status
    );

    Optional<BorrowedFund> findByIdAndUserId(Long id, String userId);
}
