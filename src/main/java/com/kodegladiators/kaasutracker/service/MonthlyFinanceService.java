package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.dto.AdditionalFundCollectionResource;
import com.kodegladiators.kaasutracker.dto.AdditionalFundRequest;
import com.kodegladiators.kaasutracker.dto.AdditionalFundResource;
import com.kodegladiators.kaasutracker.dto.CarryForwardResource;
import com.kodegladiators.kaasutracker.dto.FundCollectionResource;
import com.kodegladiators.kaasutracker.dto.FundRequest;
import com.kodegladiators.kaasutracker.dto.FundResource;
import com.kodegladiators.kaasutracker.dto.CarryForwardResource;
import com.kodegladiators.kaasutracker.dto.ExpenseResource;
import com.kodegladiators.kaasutracker.dto.ExpenseFundingResource;
import com.kodegladiators.kaasutracker.dto.FundingAllocationRequest;
import com.kodegladiators.kaasutracker.dto.FundingAllocationResource;
import com.kodegladiators.kaasutracker.dto.MonthlyFinancialSummary;
import com.kodegladiators.kaasutracker.dto.MonthlySalaryRequest;
import com.kodegladiators.kaasutracker.dto.RefundRequest;
import com.kodegladiators.kaasutracker.model.AdditionalFund;
import com.kodegladiators.kaasutracker.model.BorrowedFund;
import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import com.kodegladiators.kaasutracker.model.Expense;
import com.kodegladiators.kaasutracker.model.ExpenseFundingAllocation;
import com.kodegladiators.kaasutracker.model.FundStatus;
import com.kodegladiators.kaasutracker.model.FundingSource;
import com.kodegladiators.kaasutracker.model.MonthlyOpeningBalance;
import com.kodegladiators.kaasutracker.model.MonthlySalary;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.AdditionalFundRepository;
import com.kodegladiators.kaasutracker.repository.BorrowedFundRepository;
import com.kodegladiators.kaasutracker.repository.EmiRepository;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentRepository;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot;
import com.kodegladiators.kaasutracker.repository.ExpenseFundingAllocationRepository;
import com.kodegladiators.kaasutracker.repository.ExpenseRepository;
import com.kodegladiators.kaasutracker.repository.MonthlyOpeningBalanceRepository;
import com.kodegladiators.kaasutracker.repository.MonthlySalaryRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class MonthlyFinanceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final UserRepository userRepository;
    private final MonthlySalaryRepository salaryRepository;
    private final BorrowedFundRepository borrowedFundRepository;
    private final AdditionalFundRepository additionalFundRepository;
    private final MonthlyOpeningBalanceRepository openingBalanceRepository;
    private final ExpenseFundingAllocationRepository allocationRepository;
    private final ExpenseRepository expenseRepository;
    private final EmiRepository emiRepository;
    private final EmiInstallmentRepository installmentRepository;
    private final Clock clock;

    public MonthlyFinanceService(
            UserRepository userRepository,
            MonthlySalaryRepository salaryRepository,
            BorrowedFundRepository borrowedFundRepository,
            AdditionalFundRepository additionalFundRepository,
            MonthlyOpeningBalanceRepository openingBalanceRepository,
            ExpenseFundingAllocationRepository allocationRepository,
            ExpenseRepository expenseRepository,
            EmiRepository emiRepository,
            EmiInstallmentRepository installmentRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.salaryRepository = salaryRepository;
        this.borrowedFundRepository = borrowedFundRepository;
        this.additionalFundRepository = additionalFundRepository;
        this.openingBalanceRepository = openingBalanceRepository;
        this.allocationRepository = allocationRepository;
        this.expenseRepository = expenseRepository;
        this.emiRepository = emiRepository;
        this.installmentRepository = installmentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MonthlyFinancialSummary summary(String userId, int year, int month) {
        requireValidPeriod(year, month);
        User user = requireUser(userId);
        YearMonth period = YearMonth.of(year, month);
        return calculateSummary(user, period);
    }

    @Transactional
    public MonthlySalaryRequest saveSalary(String userId, MonthlySalaryRequest request) {
        requireValidPeriod(request.year(), request.month());
        User user = requireUserForUpdate(userId);
        MonthlySalary salary = salaryRepository
                .findByUserIdAndYearAndMonth(userId, request.year(), request.month())
                .orElseGet(MonthlySalary::new);
        salary.setUser(user);
        salary.setYear(request.year());
        salary.setMonth(request.month());
        salary.setAmount(request.amount());
        salaryRepository.save(salary);
        recalculateMonthAllocations(userId, YearMonth.of(request.year(), request.month()));
        return request;
    }

    @Transactional
    public FundResource createBorrowedFund(String userId, FundRequest request) {
        User user = requireUserForUpdate(userId);
        BorrowedFund fund = new BorrowedFund();
        fund.setUser(user);
        applyBorrowed(fund, request);
        BorrowedFund saved = borrowedFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(saved.getFundDate()));
        return toResource(saved);
    }

    @Transactional
    public FundResource updateBorrowedFund(String userId, long id, FundRequest request) {
        requireUserForUpdate(userId);
        BorrowedFund fund = borrowedFundRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> notFound("Borrowed fund not found"));
        ensureFundUpdateAllowed(FundingSource.BORROWED, id, request.amount(), fund.getStatus(), userId);
        ensureFundPeriodUnchanged(
                FundingSource.BORROWED, id, fund.getFundDate(), request.fundDate(), userId);
        applyBorrowed(fund, request);
        BorrowedFund saved = borrowedFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(saved.getFundDate()));
        return toResource(saved);
    }

    @Transactional
    public void deactivateBorrowedFund(String userId, long id) {
        requireUserForUpdate(userId);
        BorrowedFund fund = borrowedFundRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> notFound("Borrowed fund not found"));
        ensureFundUpdateAllowed(FundingSource.BORROWED, id, BigDecimal.ZERO, FundStatus.INACTIVE, userId);
        fund.setStatus(FundStatus.INACTIVE);
        borrowedFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(fund.getFundDate()));
    }

    @Transactional(readOnly = true)
    public AdditionalFundCollectionResource additionalFunds(
            String userId, Integer year, Integer month
    ) {
        requireUser(userId);
        validateOptionalPeriod(year, month);
        List<AdditionalFund> funds;
        BigDecimal received;
        BigDecimal used;
        if (year == null || month == null) {
            funds = additionalFundRepository.findAllByUserIdOrderByFundDateDesc(userId);
            received = funds.stream()
                    .filter(fund -> fund.getStatus() == FundStatus.AVAILABLE)
                    .map(AdditionalFund::getAmount).reduce(ZERO, BigDecimal::add);
            used = funds.stream()
                    .filter(fund -> fund.getStatus() == FundStatus.AVAILABLE)
                    .map(fund -> usedForSource(userId, FundingSource.ADDITIONAL, fund.getId(),
                            YearMonth.from(fund.getFundDate())))
                    .reduce(ZERO, BigDecimal::add);
        } else {
            requireValidPeriod(year, month);
            YearMonth period = YearMonth.of(year, month);
            funds = additionalFundRepository
                    .findAllByUserIdAndFundDateBetweenAndStatus(
                            userId, period.atDay(1), period.atEndOfMonth(), FundStatus.AVAILABLE);
            received = sumAdditional(funds);
            used = sumForPeriod(userId, FundingSource.ADDITIONAL, period);
        }
        return new AdditionalFundCollectionResource(
                funds.stream().map(MonthlyFinanceService::toResource).toList(),
                received,
                used,
                nonNegative(received.subtract(used))
        );
    }

    @Transactional
    public AdditionalFundResource createAdditionalFund(String userId, AdditionalFundRequest request) {
        User user = requireUserForUpdate(userId);
        AdditionalFund fund = new AdditionalFund();
        fund.setUser(user);
        applyAdditional(fund, request);
        AdditionalFund saved = additionalFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(saved.getFundDate()));
        return toResource(saved);
    }

    @Transactional
    public AdditionalFundResource updateAdditionalFund(String userId, long id, AdditionalFundRequest request) {
        requireUserForUpdate(userId);
        AdditionalFund fund = additionalFundRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("Additional fund not found"));
        ensureFundUpdateAllowed(FundingSource.ADDITIONAL, id, request.amount(), FundStatus.AVAILABLE, userId);
        ensureFundPeriodUnchanged(
                FundingSource.ADDITIONAL, id, fund.getFundDate(), request.fundDate(), userId);
        applyAdditional(fund, request);
        AdditionalFund saved = additionalFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(saved.getFundDate()));
        return toResource(saved);
    }

    @Transactional
    public void deactivateAdditionalFund(String userId, long id) {
        requireUserForUpdate(userId);
        AdditionalFund fund = additionalFundRepository.findByIdAndUserIdForUpdate(id, userId)
                .orElseThrow(() -> notFound("Additional fund not found"));
        ensureFundUpdateAllowed(FundingSource.ADDITIONAL, id, BigDecimal.ZERO, FundStatus.INACTIVE, userId);
        fund.setStatus(FundStatus.INACTIVE);
        additionalFundRepository.save(fund);
        recalculateMonthAllocations(userId, YearMonth.from(fund.getFundDate()));
    }

    @Transactional(readOnly = true)
    public FundCollectionResource borrowedFunds(String userId, Integer year, Integer month) {
        requireUser(userId);
        validateOptionalPeriod(year, month);
        List<BorrowedFund> funds;
        BigDecimal received;
        BigDecimal used;
        if (year == null || month == null) {
            funds = borrowedFundRepository.findAllByUserIdOrderByFundDateDesc(userId);
            received = funds.stream()
                    .filter(fund -> fund.getStatus() == FundStatus.AVAILABLE)
                    .map(BorrowedFund::getAmount).reduce(ZERO, BigDecimal::add);
            used = funds.stream()
                    .filter(fund -> fund.getStatus() == FundStatus.AVAILABLE)
                    .map(fund -> usedForSource(userId, FundingSource.BORROWED, fund.getId(),
                            YearMonth.from(fund.getFundDate())))
                    .reduce(ZERO, BigDecimal::add);
        } else {
            requireValidPeriod(year, month);
            YearMonth period = YearMonth.of(year, month);
            funds = borrowedFundRepository.findAllByUserIdAndFundDateBetweenAndStatus(
                    userId, period.atDay(1), period.atEndOfMonth(), FundStatus.AVAILABLE);
            received = sumBorrowed(funds);
            used = sumForPeriod(userId, FundingSource.BORROWED, period);
        }
        return new FundCollectionResource(
                funds.stream().map(MonthlyFinanceService::toResource).toList(),
                received,
                used,
                nonNegative(received.subtract(used))
        );
    }

    @Transactional
    public CarryForwardResource establishCarryForward(String userId, int year, int month) {
        requireValidPeriod(year, month);
        User user = requireUserForUpdate(userId);
        MonthlyOpeningBalance existing = openingBalanceRepository
                .findByUserIdAndYearAndMonth(userId, year, month).orElse(null);
        if (existing != null) {
            recalculateMonthAllocations(userId, YearMonth.of(year, month));
            return toCarryForwardResource(existing, calculateSummary(user, YearMonth.of(year, month)));
        }
        YearMonth period = YearMonth.of(year, month);
        if (!period.equals(YearMonth.of(1, 1))) {
            recalculateMonthAllocations(userId, period.minusMonths(1));
        }
        BigDecimal carryForward = calculatedClosingBalance(user, period);
        MonthlyOpeningBalance opening = new MonthlyOpeningBalance();
        opening.setUser(user);
        opening.setYear(year);
        opening.setMonth(month);
        opening.setAmount(nonNegative(carryForward));
        openingBalanceRepository.save(opening);
        recalculateMonthAllocations(userId, YearMonth.of(year, month));
        return toCarryForwardResource(opening, calculateSummary(user, YearMonth.of(year, month)));
    }

    @Transactional
    public CarryForwardResource carryForward(String userId, int year, int month) {
        requireValidPeriod(year, month);
        User user = requireUserForUpdate(userId);
        YearMonth period = YearMonth.of(year, month);
        MonthlyOpeningBalance opening = openingBalanceRepository
                .findByUserIdAndYearAndMonth(userId, year, month).orElse(null);
        if (opening == null) {
            if (!period.equals(YearMonth.of(1, 1))) {
                recalculateMonthAllocations(userId, period.minusMonths(1));
            }
            return new CarryForwardResource(
                    null, year, month, ZERO, calculatedClosingBalance(user, period), calculateSummary(user, period));
        }
        recalculateMonthAllocations(userId, period);
        return toCarryForwardResource(opening, calculateSummary(user, period));
    }

    @Transactional
    public ExpenseFundingResource replaceAllocations(
            String userId,
            long expenseId,
            FundingAllocationRequest request
    ) {
        requireUserForUpdate(userId);
        Expense expense = expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> notFound("Expense not found"));
        if (expense.getEmiInstallment() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduled EMI payment funding cannot be edited");
        }
        if (!expense.isConfirmed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only confirmed expenses can be funded");
        }
        if (expense.getRefundedAmount().signum() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Funding cannot be edited after a refund");
        }

        YearMonth period = YearMonth.from(expense.getExpenseDate());
        List<ExpenseFundingAllocation> requestedAllocations = new java.util.ArrayList<>();
        Set<AllocationSourceKey> allocationSources = new HashSet<>();
        BigDecimal requestedTotal = ZERO;
        for (FundingAllocationRequest.AllocationItem item : request.allocations()) {
            ExpenseFundingAllocation allocation = resolveAllocation(userId, expense, period, item);
            AllocationSourceKey key = new AllocationSourceKey(allocation.getFundingSource(), allocation.getSourceId());
            if (!allocationSources.add(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate funding source allocation");
            }
            requestedTotal = requestedTotal.add(item.amount());
            allocation.setExpense(expense);
            allocation.setAmount(item.amount());
            requestedAllocations.add(allocation);
        }
        if (requestedTotal.compareTo(expense.getAmount()) != 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Funding allocations must equal the expense amount");
        }

        allocationRepository.deleteAll(allocationRepository.findAllByExpenseId(expenseId));
        allocationRepository.flush();
        expense.setFundingAllocationManual(true);
        expenseRepository.save(expense);
        allocationRepository.saveAll(requestedAllocations);
        allocationRepository.flush();
        recalculateMonthAllocations(userId, period);
        return new ExpenseFundingResource(
                toExpenseResource(expense),
                sortedAllocations(expenseId)
        );
    }

    @Transactional
    public void recalculateMonthFunding(String userId, int year, int month) {
        requireValidPeriod(year, month);
        requireUserForUpdate(userId);
        recalculateMonthAllocations(userId, YearMonth.of(year, month));
    }

    @Transactional
    public void removeExpenseAllocations(String userId, long expenseId) {
        requireUserForUpdate(userId);
        expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> notFound("Expense not found"));
        allocationRepository.deleteAll(allocationRepository.findAllByExpenseId(expenseId));
        allocationRepository.flush();
    }

    @Transactional(readOnly = true)
    public List<FundingAllocationResource> allocations(String userId, long expenseId) {
        requireUser(userId);
        expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> notFound("Expense not found"));
        return sortedAllocations(expenseId);
    }

    private List<FundingAllocationResource> sortedAllocations(long expenseId) {
        return allocationRepository.findAllByExpenseId(expenseId).stream()
                .sorted(java.util.Comparator.comparing(ExpenseFundingAllocation::getFundingSource)
                        .thenComparing(ExpenseFundingAllocation::getSourceId,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .map(MonthlyFinanceService::toResource).toList();
    }

    @Transactional
    public BigDecimal recordRefund(String userId, long expenseId, RefundRequest request) {
        requireUserForUpdate(userId);
        Expense expense = expenseRepository.findByIdAndUserId(expenseId, userId)
                .orElseThrow(() -> notFound("Expense not found"));
        BigDecimal newRefunded = expense.getRefundedAmount().add(request.amount());
        if (newRefunded.compareTo(expense.getAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund exceeds the expense amount");
        }
        if (expense.isFundingAllocationManual()) {
            expense.setFundingAllocationManual(false);
        }
        expense.setRefundedAmount(newRefunded);
        expenseRepository.save(expense);
        recalculateMonthAllocations(userId, YearMonth.from(expense.getExpenseDate()));
        return newRefunded;
    }

    private MonthlyFinancialSummary calculateSummary(User user, YearMonth period) {
        String userId = user.getId();
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        BigDecimal salary = salaryRepository.findByUserIdAndYearAndMonth(userId, period.getYear(), period.getMonthValue())
                .map(MonthlySalary::getAmount)
                .orElse(zeroIfNull(user.getBaseSalary()));
        BigDecimal opening = openingBalanceRepository.findByUserIdAndYearAndMonth(
                        userId, period.getYear(), period.getMonthValue())
                .map(MonthlyOpeningBalance::getAmount).orElse(ZERO);
        BigDecimal borrowed = sumBorrowed(borrowedFundRepository
                .findAllByUserIdAndFundDateBetweenAndStatus(userId, start, end, FundStatus.AVAILABLE));
        BigDecimal additional = sumAdditional(additionalFundRepository
                .findAllByUserIdAndFundDateBetweenAndStatus(userId, start, end, FundStatus.AVAILABLE));
        BigDecimal expenses = expenseRepository.sumEligibleNetAmount(userId, start, end);

        BigDecimal salaryUsed = sumForPeriod(userId, FundingSource.SALARY, period);
        BigDecimal carryUsed = sumForPeriod(userId, FundingSource.CARRY_FORWARD, period);
        BigDecimal borrowedUsed = sumForPeriod(userId, FundingSource.BORROWED, period);
        BigDecimal additionalUsed = sumForPeriod(userId, FundingSource.ADDITIONAL, period);
        salaryUsed = salaryUsed.min(salary);
        carryUsed = carryUsed.min(opening);
        borrowedUsed = borrowedUsed.min(borrowed);
        additionalUsed = additionalUsed.min(additional);
        BigDecimal allocated = salaryUsed.add(carryUsed).add(borrowedUsed).add(additionalUsed);
        BigDecimal remainingSalary = nonNegative(salary.subtract(salaryUsed));
        BigDecimal remainingCarryForward = nonNegative(opening.subtract(carryUsed));
        BigDecimal remainingBorrowed = nonNegative(borrowed.subtract(borrowedUsed));
        BigDecimal remainingAdditional = nonNegative(additional.subtract(additionalUsed));
        BigDecimal totalAvailable = remainingSalary
                .add(remainingCarryForward)
                .add(remainingBorrowed)
                .add(remainingAdditional);
        List<Emi> activeEmis = emiRepository.findAllByUserIdAndStatusOrderByIdAsc(userId, EmiStatus.ACTIVE);
        LocalDate today = LocalDate.now(clock);
        List<EmiInstallmentSnapshot> installmentSnapshots =
                installmentRepository.findAllSnapshotsByUserId(userId);
        Map<EmiInstallmentKey, EmiInstallmentSnapshot> installmentsByDate = new HashMap<>();
        BigDecimal emiScheduled = ZERO;
        BigDecimal emiPaid = ZERO;
        for (EmiInstallmentSnapshot snapshot : installmentSnapshots) {
            installmentsByDate.put(new EmiInstallmentKey(snapshot.emiId(), snapshot.dueDate()), snapshot);
            if (!snapshot.dueDate().isBefore(start) && !snapshot.dueDate().isAfter(end)) {
                if (snapshot.status() == EmiInstallmentStatus.PAID) {
                    emiPaid = emiPaid.add(snapshot.amount());
                    emiScheduled = emiScheduled.add(snapshot.amount());
                }
            }
        }
        BigDecimal emiDueToday = ZERO;
        BigDecimal emiDueTodayUnpaid = ZERO;
        LocalDate nextDueDate = null;
        for (Emi emi : activeEmis) {
            for (LocalDate dueDate : EmiSchedule.installmentDatesBetween(
                    emi, emi.getStartedOn(), end)) {
                EmiInstallmentSnapshot snapshot =
                        installmentsByDate.get(new EmiInstallmentKey(emi.getId(), dueDate));
                boolean paid = snapshot != null && snapshot.status() == EmiInstallmentStatus.PAID;
                if (!paid) {
                    emiScheduled = emiScheduled.add(snapshot == null ? emi.getAmount() : snapshot.amount());
                }
                if (dueDate.equals(today)) {
                    emiDueToday = emiDueToday.add(emi.getAmount());
                    if (!paid) {
                        emiDueTodayUnpaid = emiDueTodayUnpaid.add(emi.getAmount());
                    }
                }
                if (!paid && (nextDueDate == null || dueDate.isBefore(nextDueDate))) {
                    nextDueDate = dueDate;
                }
            }
            LocalDate candidate = EmiSchedule.nextDueDateAfter(emi, end);
            while (candidate != null) {
                EmiInstallmentSnapshot snapshot =
                        installmentsByDate.get(new EmiInstallmentKey(emi.getId(), candidate));
                if (snapshot == null || snapshot.status() != EmiInstallmentStatus.PAID) {
                    nextDueDate = nextDueDate == null || candidate.isBefore(nextDueDate)
                            ? candidate : nextDueDate;
                    break;
                }
                candidate = EmiSchedule.nextDueDateAfter(emi, candidate);
            }
        }
        String nextEmiStatus = nextDueDate == null
                ? null
                : nextDueDate.isBefore(today) ? "OVERDUE" : nextDueDate.equals(today) ? "DUE" : "SCHEDULED";

        return new MonthlyFinancialSummary(
                period.getMonthValue(),
                period.getYear(),
                salary,
                opening,
                additional,
                borrowed,
                expenses,
                salaryUsed,
                additionalUsed,
                carryUsed,
                borrowedUsed,
                allocated,
                remainingSalary,
                remainingCarryForward,
                remainingAdditional,
                remainingBorrowed,
                totalAvailable,
                nonNegative(expenses.subtract(allocated)),
                emiScheduled,
                nextDueDate,
                nextEmiStatus,
                emiPaid,
                emiDueToday,
                emiDueTodayUnpaid
        );
    }

    private record EmiInstallmentKey(Long emiId, LocalDate dueDate) {
    }

    private void recalculateMonthAllocations(String userId, YearMonth period) {
        recalculateSingleMonthAllocations(userId, period);
        User user = requireUser(userId);
        for (MonthlyOpeningBalance opening : openingBalanceRepository
                .findAllByUserIdOrderByYearAscMonthAsc(userId)) {
            YearMonth openingPeriod = YearMonth.of(opening.getYear(), opening.getMonth());
            if (!openingPeriod.isAfter(period)) {
                continue;
            }
            BigDecimal closingBalance = calculatedClosingBalance(user, openingPeriod);
            if (opening.getAmount().compareTo(closingBalance) != 0) {
                opening.setAmount(nonNegative(closingBalance));
                openingBalanceRepository.save(opening);
            }
            recalculateSingleMonthAllocations(userId, openingPeriod);
        }
    }

    private void recalculateSingleMonthAllocations(String userId, YearMonth period) {
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        List<Expense> expenses = expenseRepository.findAllByUserIdAndExpenseDateBetweenOrderByExpenseDateAscIdAsc(
                userId, start, end);
        for (Expense expense : expenses) {
            if (!expense.isFundingAllocationManual()) {
                allocationRepository.deleteAll(allocationRepository.findAllByExpenseId(expense.getId()));
            }
        }
        allocationRepository.flush();

        User user = requireUser(userId);
        BigDecimal salaryBalance = nonNegative(salaryRepository
                .findByUserIdAndYearAndMonth(userId, period.getYear(), period.getMonthValue())
                .map(MonthlySalary::getAmount)
                .orElse(zeroIfNull(user.getBaseSalary())));
        BigDecimal monthlySalaryAmount = salaryBalance;
        MonthlyOpeningBalance opening = openingBalanceRepository
                .findByUserIdAndYearAndMonth(userId, period.getYear(), period.getMonthValue()).orElse(null);
        BigDecimal carryForwardBalance = opening == null ? ZERO : nonNegative(opening.getAmount());
        List<BorrowedFund> borrowedFunds = borrowedFundRepository
                .findAllByUserIdAndFundDateBetweenAndStatus(userId, start, end, FundStatus.AVAILABLE)
                .stream()
                .sorted(java.util.Comparator.comparing(BorrowedFund::getFundDate)
                        .thenComparing(BorrowedFund::getId))
                .toList();
        java.util.Map<Long, BigDecimal> borrowedBalances = new java.util.LinkedHashMap<>();
        for (BorrowedFund borrowedFund : borrowedFunds) {
            borrowedBalances.put(borrowedFund.getId(), nonNegative(borrowedFund.getAmount()));
        }

        List<AdditionalFund> additionalFunds = additionalFundRepository
                .findAllByUserIdAndFundDateBetweenAndStatus(userId, start, end, FundStatus.AVAILABLE)
                .stream()
                .sorted(java.util.Comparator.comparing(AdditionalFund::getFundDate)
                        .thenComparing(AdditionalFund::getId))
                .toList();
        java.util.Map<Long, BigDecimal> additionalBalances = new java.util.LinkedHashMap<>();
        for (AdditionalFund additionalFund : additionalFunds) {
            additionalBalances.put(additionalFund.getId(), nonNegative(additionalFund.getAmount()));
        }

        MonthlySalary salary = salaryRepository
                .findByUserIdAndYearAndMonth(userId, period.getYear(), period.getMonthValue())
                .orElse(null);
        for (Expense expense : expenses) {
            if (!expense.isConfirmed() || !expense.isFundingAllocationManual()) {
                continue;
            }
            BigDecimal manuallyAllocated = ZERO;
            for (ExpenseFundingAllocation allocation : allocationRepository.findAllByExpenseId(expense.getId())) {
                manuallyAllocated = manuallyAllocated.add(allocation.getAmount());
                switch (allocation.getFundingSource()) {
                    case SALARY -> {
                        if (salary == null || allocation.getSalary() == null
                                || !allocation.getSalary().getId().equals(salary.getId())
                                || allocation.getAmount().compareTo(salaryBalance) > 0) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Manual salary allocation exceeds available funds");
                        }
                        salaryBalance = nonNegative(salaryBalance.subtract(allocation.getAmount()));
                    }
                    case CARRY_FORWARD -> {
                        if (opening == null || allocation.getOpeningBalance() == null
                                || !allocation.getOpeningBalance().getId().equals(opening.getId())
                                || allocation.getAmount().compareTo(carryForwardBalance) > 0) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Manual carry-forward allocation exceeds available funds");
                        }
                        carryForwardBalance = nonNegative(carryForwardBalance.subtract(allocation.getAmount()));
                    }
                    case BORROWED -> {
                        Long borrowedId = allocation.getBorrowedFund() == null
                                ? null : allocation.getBorrowedFund().getId();
                        BigDecimal available = borrowedId == null ? null : borrowedBalances.get(borrowedId);
                        if (available == null || allocation.getAmount().compareTo(available) > 0) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Manual borrowed-fund allocation exceeds available funds");
                        }
                        borrowedBalances.put(
                                borrowedId, nonNegative(available.subtract(allocation.getAmount())));
                    }
                    case ADDITIONAL -> {
                        Long additionalId = allocation.getAdditionalFund() == null
                                ? null : allocation.getAdditionalFund().getId();
                        BigDecimal available = additionalId == null ? null : additionalBalances.get(additionalId);
                        if (available == null || allocation.getAmount().compareTo(available) > 0) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Manual additional-fund allocation exceeds available funds");
                        }
                        additionalBalances.put(
                                additionalId, nonNegative(available.subtract(allocation.getAmount())));
                    }
                }
            }
            BigDecimal netExpense = expense.getAmount().subtract(zeroIfNull(expense.getRefundedAmount()));
            if (manuallyAllocated.compareTo(netExpense) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Manual allocation total is no longer valid");
            }
        }

        for (Expense expense : expenses) {
            if (!expense.isConfirmed() || expense.isFundingAllocationManual()) {
                continue;
            }
            BigDecimal remaining = nonNegative(expense.getAmount().subtract(zeroIfNull(expense.getRefundedAmount())));
            BigDecimal salaryUsed = remaining.min(salaryBalance);
            if (salaryUsed.signum() > 0) {
                if (salary == null) {
                    salary = salaryRepository.findByUserIdAndYearAndMonth(
                                    userId, period.getYear(), period.getMonthValue())
                            .orElseGet(() -> {
                                MonthlySalary newSalary = new MonthlySalary();
                                newSalary.setUser(user);
                                newSalary.setYear(period.getYear());
                                newSalary.setMonth(period.getMonthValue());
                                newSalary.setAmount(monthlySalaryAmount);
                                return salaryRepository.save(newSalary);
                            });
                }
                saveAllocation(expense, FundingSource.SALARY, salaryUsed, salary, null, null, null);
                salaryBalance = nonNegative(salaryBalance.subtract(salaryUsed));
                remaining = nonNegative(remaining.subtract(salaryUsed));
            }

            BigDecimal carryForwardUsed = remaining.min(carryForwardBalance);
            if (carryForwardUsed.signum() > 0 && opening != null) {
                saveAllocation(expense, FundingSource.CARRY_FORWARD, carryForwardUsed, null, opening, null, null);
                carryForwardBalance = nonNegative(carryForwardBalance.subtract(carryForwardUsed));
                remaining = nonNegative(remaining.subtract(carryForwardUsed));
            }

            for (AdditionalFund additionalFund : additionalFunds) {
                if (remaining.signum() == 0) {
                    break;
                }
                BigDecimal available = additionalBalances.get(additionalFund.getId());
                BigDecimal additionalUsed = remaining.min(available);
                if (additionalUsed.signum() > 0) {
                    saveAllocation(expense, FundingSource.ADDITIONAL, additionalUsed,
                            null, null, additionalFund, null);
                    additionalBalances.put(
                            additionalFund.getId(), nonNegative(available.subtract(additionalUsed)));
                    remaining = nonNegative(remaining.subtract(additionalUsed));
                }
            }

            for (BorrowedFund borrowedFund : borrowedFunds) {
                if (remaining.signum() == 0) {
                    break;
                }
                BigDecimal available = borrowedBalances.get(borrowedFund.getId());
                BigDecimal borrowedUsed = remaining.min(available);
                if (borrowedUsed.signum() > 0) {
                    saveAllocation(expense, FundingSource.BORROWED, borrowedUsed, null, null, null, borrowedFund);
                    borrowedBalances.put(
                            borrowedFund.getId(), nonNegative(available.subtract(borrowedUsed)));
                    remaining = nonNegative(remaining.subtract(borrowedUsed));
                }
            }
        }
    }

    private ExpenseFundingAllocation saveAllocation(
            Expense expense,
            FundingSource source,
            BigDecimal amount,
            MonthlySalary salary,
            MonthlyOpeningBalance opening,
            AdditionalFund additionalFund,
            BorrowedFund borrowedFund
    ) {
        ExpenseFundingAllocation allocation = new ExpenseFundingAllocation();
        allocation.setExpense(expense);
        allocation.setFundingSource(source);
        allocation.setAmount(amount);
        allocation.setSalary(salary);
        allocation.setOpeningBalance(opening);
        allocation.setAdditionalFund(additionalFund);
        allocation.setBorrowedFund(borrowedFund);
        return allocationRepository.save(allocation);
    }

    private ExpenseFundingAllocation resolveAllocation(
            String userId,
            Expense expense,
            YearMonth period,
            FundingAllocationRequest.AllocationItem item
    ) {
        ExpenseFundingAllocation allocation = new ExpenseFundingAllocation();
        allocation.setFundingSource(item.fundingSource());
        switch (item.fundingSource()) {
            case SALARY -> {
                MonthlySalary salary = salaryRepository
                        .findByUserIdAndYearAndMonth(userId, period.getYear(), period.getMonthValue())
                        .orElse(null);
                if (salary == null) {
                    User user = requireUser(userId);
                    salary = new MonthlySalary();
                    salary.setUser(user);
                    salary.setYear(period.getYear());
                    salary.setMonth(period.getMonthValue());
                    salary.setAmount(nonNegative(zeroIfNull(user.getBaseSalary())));
                    salary = salaryRepository.saveAndFlush(salary);
                }
                if (item.sourceId() != null && !item.sourceId().equals(salary.getId())) {
                    throw invalidFundingSource();
                }
                allocation.setSalary(salary);
            }
            case CARRY_FORWARD -> {
                MonthlyOpeningBalance opening = item.sourceId() == null
                        ? null : openingBalanceRepository.findById(item.sourceId()).orElse(null);
                if (opening == null
                        || !opening.getUser().getId().equals(userId)
                        || opening.getYear() != period.getYear()
                        || opening.getMonth() != period.getMonthValue()) {
                    throw invalidFundingSource();
                }
                allocation.setOpeningBalance(opening);
            }
            case BORROWED -> {
                BorrowedFund borrowed = item.sourceId() == null
                        ? null : borrowedFundRepository.findById(item.sourceId()).orElse(null);
                if (borrowed == null
                        || !borrowed.getUser().getId().equals(userId)
                        || borrowed.getStatus() != FundStatus.AVAILABLE
                        || !YearMonth.from(borrowed.getFundDate()).equals(period)) {
                    throw invalidFundingSource();
                }
                allocation.setBorrowedFund(borrowed);
            }
            case ADDITIONAL -> {
                AdditionalFund additional = item.sourceId() == null
                        ? null : additionalFundRepository.findById(item.sourceId()).orElse(null);
                if (additional == null
                        || !additional.getUser().getId().equals(userId)
                        || additional.getStatus() != FundStatus.AVAILABLE
                        || !YearMonth.from(additional.getFundDate()).equals(period)) {
                    throw invalidFundingSource();
                }
                allocation.setAdditionalFund(additional);
            }
        }
        return allocation;
    }

    private static ResponseStatusException invalidFundingSource() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or ineligible funding source");
    }

    private record AllocationSourceKey(FundingSource source, Long sourceId) {
    }

    private void ensureFundUpdateAllowed(
            FundingSource source, long id, BigDecimal newAmount, FundStatus newStatus, String userId
    ) {
        BigDecimal allocated = sumAllocatedToSource(
                userId, source, id, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31));
        if (newStatus != FundStatus.AVAILABLE && allocated.signum() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A fund with allocations cannot be deactivated");
        }
        if (newAmount.compareTo(allocated) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Fund amount cannot be less than allocated amount");
        }
    }

    private void ensureFundPeriodUnchanged(
            FundingSource source, long id, LocalDate existingDate, LocalDate requestedDate, String userId
    ) {
        BigDecimal allocated = sumAllocatedToSource(
                userId, source, id, LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31));
        if (allocated.signum() > 0
                && !YearMonth.from(existingDate).equals(YearMonth.from(requestedDate))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A fund with allocations cannot be moved to another month");
        }
    }

    private BigDecimal usedForSource(String userId, FundingSource source, Long sourceId, YearMonth period) {
        return sumAllocatedToSource(
                userId, source, sourceId, period.atDay(1), period.atEndOfMonth());
    }

    private BigDecimal sumAllocatedToSource(
            String userId, FundingSource source, Long sourceId, LocalDate start, LocalDate end
    ) {
        return switch (source) {
            case SALARY -> allocationRepository.sumForUserAndPeriod(userId, source, start, end);
            case CARRY_FORWARD -> allocationRepository.sumForCarryForward(userId, sourceId, start, end);
            case ADDITIONAL -> allocationRepository.sumForAdditionalFund(userId, sourceId, start, end);
            case BORROWED -> allocationRepository.sumForBorrowedFund(userId, sourceId, start, end);
        };
    }

    private BigDecimal sumForPeriod(String userId, FundingSource source, YearMonth period) {
        return allocationRepository.sumForUserAndPeriod(
                userId, source, period.atDay(1), period.atEndOfMonth());
    }

    private static BigDecimal sumBorrowed(List<BorrowedFund> funds) {
        return funds.stream().map(BorrowedFund::getAmount).reduce(ZERO, BigDecimal::add);
    }

    private static CarryForwardResource toCarryForwardResource(
            MonthlyOpeningBalance opening, MonthlyFinancialSummary summary
    ) {
        return new CarryForwardResource(
                opening.getId(), opening.getYear(), opening.getMonth(), opening.getAmount(),
                opening.getAmount(), summary);
    }

    private BigDecimal calculatedClosingBalance(User user, YearMonth period) {
        if (period.equals(YearMonth.of(1, 1))) {
            return ZERO;
        }
        MonthlyFinancialSummary previous = calculateSummary(user, period.minusMonths(1));
        return previous.remainingSalaryAvailable()
                .add(previous.remainingCarryForwardAvailable())
                .add(previous.remainingAdditionalFundsAvailable())
                .add(previous.remainingBorrowedFundsAvailable());
    }

    private static void validateOptionalPeriod(Integer year, Integer month) {
        if ((year == null) != (month == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Year and month must be provided together");
        }
        if (year != null) {
            requireValidPeriod(year, month);
        }
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> notFound("User not found"));
    }

    private User requireUserForUpdate(String userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("User not found"));
    }

    private static void applyBorrowed(BorrowedFund fund, FundRequest request) {
        if (request.outstandingRepaymentBalance() != null
                && request.outstandingRepaymentBalance().compareTo(request.amount()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Outstanding repayment cannot exceed the borrowed amount");
        }
        fund.setAmount(request.amount());
        fund.setFundDate(request.fundDate());
        fund.setSourceDescription(request.sourceDescription().trim());
        fund.setPurpose(request.purpose());
        fund.setOutstandingRepaymentBalance(request.outstandingRepaymentBalance());
        fund.setStatus(FundStatus.AVAILABLE);
    }

    private static FundResource toResource(BorrowedFund fund) {
        return new FundResource(fund.getId(), fund.getAmount(), fund.getFundDate(),
                fund.getSourceDescription(), fund.getPurpose(),
                fund.getOutstandingRepaymentBalance(), fund.getStatus());
    }

    private static AdditionalFundResource toResource(AdditionalFund fund) {
        return new AdditionalFundResource(
                fund.getId(), fund.getAmount(), fund.getFundDate(),
                fund.getSourceDescription(), fund.getPurpose(), fund.getStatus()
        );
    }

    private static void applyAdditional(AdditionalFund fund, AdditionalFundRequest request) {
        fund.setAmount(request.amount());
        fund.setFundDate(request.fundDate());
        fund.setSourceDescription(request.sourceDescription().trim());
        fund.setPurpose(request.purpose());
        fund.setStatus(FundStatus.AVAILABLE);
    }

    private static BigDecimal sumAdditional(List<AdditionalFund> funds) {
        return funds.stream().map(AdditionalFund::getAmount).reduce(ZERO, BigDecimal::add);
    }

    private static FundingAllocationResource toResource(ExpenseFundingAllocation allocation) {
        return new FundingAllocationResource(allocation.getId(), allocation.getFundingSource(),
                allocation.getSourceId(), allocation.getAmount());
    }

    private static ExpenseResource toExpenseResource(Expense expense) {
        return new ExpenseResource(
                expense.getId(), expense.getAmount(), expense.getRefundedAmount(), expense.getCategory(),
                expense.getMerchant(), expense.getDescription(), expense.getExpenseDate(),
                expense.getReceiptUrl(), expense.isConfirmed());
    }

    private static BigDecimal nonNegative(BigDecimal amount) {
        return amount.signum() < 0 ? ZERO : amount;
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? ZERO : amount;
    }

    private static void requireValidPeriod(int year, int month) {
        if (year < 1 || year > 9999 || month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Month and year are invalid");
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
