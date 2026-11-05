package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.MonthlySalary;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlySalaryRepository extends JpaRepository<MonthlySalary, Long> {
    Optional<MonthlySalary> findByUserIdAndYearAndMonth(String userId, int year, int month);
}
