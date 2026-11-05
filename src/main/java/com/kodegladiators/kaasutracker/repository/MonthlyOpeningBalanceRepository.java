package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.MonthlyOpeningBalance;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyOpeningBalanceRepository extends JpaRepository<MonthlyOpeningBalance, Long> {
    Optional<MonthlyOpeningBalance> findByUserIdAndYearAndMonth(String userId, int year, int month);

    List<MonthlyOpeningBalance> findAllByUserIdOrderByYearAscMonthAsc(String userId);
}
