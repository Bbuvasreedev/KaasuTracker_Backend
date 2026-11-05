package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.dto.EmiCreateRequest;
import com.kodegladiators.kaasutracker.dto.EmiDurationRequest;
import com.kodegladiators.kaasutracker.dto.EmiResource;
import com.kodegladiators.kaasutracker.model.Emi;
import com.kodegladiators.kaasutracker.model.EmiInstallment;
import com.kodegladiators.kaasutracker.model.EmiInstallmentStatus;
import com.kodegladiators.kaasutracker.model.EmiStatus;
import com.kodegladiators.kaasutracker.model.User;
import com.kodegladiators.kaasutracker.repository.EmiInstallmentRepository;
import com.kodegladiators.kaasutracker.repository.EmiRepository;
import com.kodegladiators.kaasutracker.repository.UserRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmiService {

    public record CreationResult(EmiResource resource, boolean created) {
    }

    private final EmiRepository emiRepository;
    private final EmiInstallmentRepository installmentRepository;
    private final UserRepository userRepository;

    public EmiService(
            EmiRepository emiRepository,
            EmiInstallmentRepository installmentRepository,
            UserRepository userRepository
    ) {
        this.emiRepository = emiRepository;
        this.installmentRepository = installmentRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<EmiResource> list(String userId) {
        List<Emi> emis = emiRepository.findAllByUserId(userId);
        Map<Long, Long> paidCounts = paidCounts(installmentRepository.findAllSnapshotsByUserId(userId));
        return emis.stream().map(emi -> toResource(emi, paidCounts.getOrDefault(emi.getId(), 0L))).toList();
    }

    @Transactional
    public CreationResult create(String userId, EmiCreateRequest request, String creationKey) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("User not found"));
        if (creationKey != null && !creationKey.isBlank()) {
            if (creationKey.trim().length() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key is too long");
            }
            Emi existing = emiRepository.findByUserIdAndCreationKey(userId, creationKey.trim()).orElse(null);
            if (existing != null) {
                if (!sameRequest(existing, request)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key was already used for a different EMI request");
                }
                long paid = installmentRepository.countByEmiIdAndStatus(
                        existing.getId(), EmiInstallmentStatus.PAID);
                return new CreationResult(toResource(existing, paid), false);
            }
        }
        Emi emi = new Emi();
        emi.setUser(user);
        emi.setTitle(request.title().trim());
        emi.setCreationKey(creationKey == null || creationKey.isBlank() ? null : creationKey.trim());
        emi.setAmount(request.amount());
        emi.setDueDateDay(request.dueDateDay());
        emi.setStartedOn(request.startDate());
        emi.setDurationMonths(request.durationMonths());
        emi.setDurationKnown(true);
        emi.setTotalInstallments(request.durationMonths());
        emi.setEndDate(EmiSchedule.endDate(emi));
        emi.setStatus(EmiStatus.ACTIVE);
        emi.setActive(true);
        Emi saved = emiRepository.saveAndFlush(emi);
        ensureInstallments(saved);
        return new CreationResult(toResource(saved, 0), true);
    }

    @Transactional
    public EmiResource updateStatus(String userId, long emiId, EmiStatus requestedStatus) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("User not found"));
        Emi emi = emiRepository.findByIdAndUserIdForUpdate(emiId, user.getId())
                .orElseThrow(() -> notFound("EMI not found"));

        if (emi.getStatus() == EmiStatus.COMPLETED && requestedStatus != EmiStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed EMIs cannot be reactivated");
        }
        if (requestedStatus == EmiStatus.COMPLETED && emi.getStatus() != EmiStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "EMIs are completed automatically after all installments are paid");
        }
        if (requestedStatus == EmiStatus.ACTIVE && emi.isDurationKnown()) {
            long paid = installmentRepository.countByEmiIdAndStatus(emiId, EmiInstallmentStatus.PAID);
            if (emi.getTotalInstallments() != null && paid >= emi.getTotalInstallments()) {
                markCompleted(emi);
                return toResource(emi, paid);
            }
        }
        emi.setStatus(requestedStatus);
        emi.setActive(requestedStatus == EmiStatus.ACTIVE);
        Emi saved = emiRepository.saveAndFlush(emi);
        if (saved.getStatus() == EmiStatus.ACTIVE && saved.isDurationKnown()) {
            ensureInstallments(saved);
        }
        return toResource(saved, installmentRepository.countByEmiIdAndStatus(emiId, EmiInstallmentStatus.PAID));
    }

    @Transactional
    public EmiResource setDuration(String userId, long emiId, EmiDurationRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> notFound("User not found"));
        Emi emi = emiRepository.findByIdAndUserIdForUpdate(emiId, user.getId())
                .orElseThrow(() -> notFound("EMI not found"));
        if (emi.getStatus() == EmiStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completed EMI terms cannot be changed");
        }
        List<EmiInstallment> existingInstallments = installmentRepository.findAllByEmiIdOrderByDueDateAsc(emiId);
        if (request.startDate() != null
                && !request.startDate().equals(emi.getStartedOn())
                && !existingInstallments.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Start date cannot change after installment history exists");
        }
        if (request.startDate() != null) {
            emi.setStartedOn(request.startDate());
        }
        long paid = existingInstallments.stream()
                .filter(installment -> installment.getStatus() == EmiInstallmentStatus.PAID)
                .count();
        if (request.durationMonths() < paid) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Duration cannot be shorter than the number of paid installments");
        }
        emi.setDurationMonths(request.durationMonths());
        emi.setDurationKnown(true);
        emi.setTotalInstallments(request.durationMonths());
        emi.setEndDate(EmiSchedule.endDate(emi));
        Emi saved = emiRepository.saveAndFlush(emi);
        ensureInstallments(saved);
        completeIfFullyPaid(saved, paid);
        return toResource(saved, paid);
    }

    @Transactional
    public void completeIfFullyPaid(Emi emi, long paidInstallments) {
        if (emi.isDurationKnown()
                && emi.getTotalInstallments() != null
                && paidInstallments >= emi.getTotalInstallments()) {
            markCompleted(emi);
        }
    }

    private void ensureInstallments(Emi emi) {
        if (!emi.isDurationKnown()) {
            return;
        }
        Map<LocalDate, EmiInstallment> existing = new HashMap<>();
        for (EmiInstallment installment : installmentRepository.findAllByEmiIdOrderByDueDateAsc(emi.getId())) {
            existing.put(installment.getDueDate(), installment);
        }
        List<EmiInstallment> missing = EmiSchedule.installmentDates(emi).stream()
                .filter(date -> !existing.containsKey(date))
                .map(date -> {
                    EmiInstallment installment = new EmiInstallment();
                    installment.setEmi(emi);
                    installment.setDueDate(date);
                    installment.setAmount(emi.getAmount());
                    installment.setStatus(EmiInstallmentStatus.PENDING);
                    return installment;
                })
                .toList();
        if (!missing.isEmpty()) {
            installmentRepository.saveAll(missing);
            installmentRepository.flush();
        }
    }

    private void markCompleted(Emi emi) {
        emi.setStatus(EmiStatus.COMPLETED);
        emi.setActive(false);
        emiRepository.saveAndFlush(emi);
    }

    private Map<Long, Long> paidCounts(List<com.kodegladiators.kaasutracker.repository.EmiInstallmentSnapshot> snapshots) {
        Map<Long, Long> counts = new HashMap<>();
        snapshots.stream()
                .filter(snapshot -> snapshot.status() == EmiInstallmentStatus.PAID)
                .forEach(snapshot -> counts.merge(snapshot.emiId(), 1L, Long::sum));
        return counts;
    }

    private static EmiResource toResource(Emi emi, long paidInstallments) {
        Integer total = emi.isDurationKnown() ? emi.getTotalInstallments() : null;
        Integer remaining = total == null ? null : (int) Math.max(0, total - paidInstallments);
        return new EmiResource(
                emi.getId(),
                emi.getTitle(),
                emi.getAmount(),
                emi.getDueDateDay(),
                emi.getStatus() == EmiStatus.ACTIVE,
                emi.getStatus(),
                emi.getStartedOn(),
                emi.isDurationKnown() ? emi.getDurationMonths() : null,
                emi.isDurationKnown() ? emi.getEndDate() : null,
                total,
                paidInstallments,
                remaining
        );
    }

    private static boolean sameRequest(Emi emi, EmiCreateRequest request) {
        return emi.getTitle().equals(request.title().trim())
                && emi.getAmount().compareTo(request.amount()) == 0
                && emi.getDueDateDay().equals(request.dueDateDay())
                && emi.getStartedOn().equals(request.startDate())
                && emi.isDurationKnown()
                && emi.getDurationMonths().equals(request.durationMonths());
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
