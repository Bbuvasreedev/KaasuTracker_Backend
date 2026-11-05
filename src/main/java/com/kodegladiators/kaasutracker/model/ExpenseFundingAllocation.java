package com.kodegladiators.kaasutracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "expense_funding_allocations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_expense_salary_source", columnNames = {"expense_id", "salary_id"}),
        @UniqueConstraint(name = "uk_expense_carry_source", columnNames = {"expense_id", "opening_balance_id"}),
        @UniqueConstraint(name = "uk_expense_additional_source", columnNames = {"expense_id", "additional_fund_id"}),
        @UniqueConstraint(name = "uk_expense_borrowed_source", columnNames = {"expense_id", "borrowed_fund_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class ExpenseFundingAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @Enumerated(EnumType.STRING)
    @Column(name = "funding_source", nullable = false, length = 20)
    private FundingSource fundingSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_id")
    private MonthlySalary salary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opening_balance_id")
    private MonthlyOpeningBalance openingBalance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "borrowed_fund_id")
    private BorrowedFund borrowedFund;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "additional_fund_id")
    private AdditionalFund additionalFund;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getSourceId() {
        return switch (fundingSource) {
            case SALARY -> salary == null ? null : salary.getId();
            case CARRY_FORWARD -> openingBalance == null ? null : openingBalance.getId();
            case BORROWED -> borrowedFund == null ? null : borrowedFund.getId();
            case ADDITIONAL -> additionalFund == null ? null : additionalFund.getId();
        };
    }
}
