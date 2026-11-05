package com.kodegladiators.kaasutracker.repository;

import com.kodegladiators.kaasutracker.model.ExpenseFundingAllocation;
import com.kodegladiators.kaasutracker.model.FundingSource;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseFundingAllocationRepository extends JpaRepository<ExpenseFundingAllocation, Long> {
    List<ExpenseFundingAllocation> findAllByExpenseId(Long expenseId);

    @Query("""
            select coalesce(sum(allocation.amount), 0)
            from ExpenseFundingAllocation allocation
            where allocation.fundingSource = :source
              and allocation.expense.user.id = :userId
              and allocation.expense.isConfirmed = true
              and allocation.expense.expenseDate between :start and :end
            """)
    BigDecimal sumForUserAndPeriod(
            @Param("userId") String userId,
            @Param("source") FundingSource source,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end
    );

    @Query("""
            select coalesce(sum(allocation.amount), 0)
            from ExpenseFundingAllocation allocation
            where allocation.openingBalance.id = :sourceId
              and allocation.expense.user.id = :userId
              and allocation.expense.isConfirmed = true
              and allocation.expense.expenseDate between :start and :end
            """)
    BigDecimal sumForCarryForward(
            @Param("userId") String userId,
            @Param("sourceId") Long sourceId,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end
    );

    @Query("""
            select coalesce(sum(allocation.amount), 0)
            from ExpenseFundingAllocation allocation
            where allocation.borrowedFund.id = :sourceId
              and allocation.expense.user.id = :userId
              and allocation.expense.isConfirmed = true
              and allocation.expense.expenseDate between :start and :end
            """)
    BigDecimal sumForBorrowedFund(
            @Param("userId") String userId,
            @Param("sourceId") Long sourceId,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end
    );

    @Query("""
            select coalesce(sum(allocation.amount), 0)
            from ExpenseFundingAllocation allocation
            where allocation.additionalFund.id = :sourceId
              and allocation.expense.user.id = :userId
              and allocation.expense.isConfirmed = true
              and allocation.expense.expenseDate between :start and :end
            """)
    BigDecimal sumForAdditionalFund(
            @Param("userId") String userId,
            @Param("sourceId") Long sourceId,
            @Param("start") java.time.LocalDate start,
            @Param("end") java.time.LocalDate end
    );
}
