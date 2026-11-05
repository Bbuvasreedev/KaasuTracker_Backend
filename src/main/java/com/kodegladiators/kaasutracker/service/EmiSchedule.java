package com.kodegladiators.kaasutracker.service;

import com.kodegladiators.kaasutracker.model.Emi;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

final class EmiSchedule {

    private EmiSchedule() {
    }

    static List<LocalDate> installmentDates(Emi emi) {
        if (!emi.isDurationKnown() || emi.getDurationMonths() == null || emi.getDurationMonths() <= 0) {
            return List.of();
        }
        return installmentDatesBetween(emi, firstDueDate(emi), YearMonth.of(9999, 12).atEndOfMonth());
    }

    static List<LocalDate> installmentDatesBetween(Emi emi, LocalDate start, LocalDate end) {
        if (emi.getStartedOn() == null || end.isBefore(start)) {
            return List.of();
        }
        LocalDate first = firstDueDate(emi);
        if (first.isAfter(end)) {
            return List.of();
        }
        YearMonth firstMonth = YearMonth.from(first);
        YearMonth startMonth = YearMonth.from(start.isAfter(first) ? start : first);
        int firstIndex = (int) java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, startMonth);
        int maxIndex = emi.isDurationKnown() && emi.getDurationMonths() != null
                ? emi.getDurationMonths() - 1
                : Integer.MAX_VALUE;
        List<LocalDate> dates = new ArrayList<>();
        for (int index = firstIndex; index <= maxIndex; index++) {
            LocalDate dueDate = dueDate(emi, firstMonth.plusMonths(index));
            if (dueDate.isAfter(end)) {
                break;
            }
            if (!dueDate.isBefore(start)) {
                dates.add(dueDate);
            }
        }
        return dates;
    }

    static List<LocalDate> installmentDatesThrough(Emi emi, LocalDate end) {
        return emi.getStartedOn() == null
                ? List.of()
                : installmentDatesBetween(emi, firstDueDate(emi), end);
    }

    static LocalDate firstDueDate(Emi emi) {
        LocalDate first = dueDate(emi, YearMonth.from(emi.getStartedOn()));
        if (first.isBefore(emi.getStartedOn())) {
            first = dueDate(emi, YearMonth.from(first).plusMonths(1));
        }
        return first;
    }

    static LocalDate endDate(Emi emi) {
        if (!emi.isDurationKnown() || emi.getDurationMonths() == null || emi.getDurationMonths() <= 0) {
            return null;
        }
        return dueDate(
                emi,
                YearMonth.from(firstDueDate(emi)).plusMonths(emi.getDurationMonths() - 1L));
    }

    static LocalDate nextDueDateAfter(Emi emi, LocalDate afterExclusive) {
        LocalDate first = firstDueDate(emi);
        YearMonth firstMonth = YearMonth.from(first);
        YearMonth candidateMonth = YearMonth.from(afterExclusive);
        if (!dueDate(emi, candidateMonth).isAfter(afterExclusive)) {
            candidateMonth = candidateMonth.plusMonths(1);
        }
        if (candidateMonth.isBefore(firstMonth)) {
            candidateMonth = firstMonth;
        }
        long index = java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, candidateMonth);
        if (emi.isDurationKnown() && emi.getDurationMonths() != null
                && index >= emi.getDurationMonths()) {
            return null;
        }
        LocalDate candidate = dueDate(emi, candidateMonth);
        return candidate.isBefore(first) ? first : candidate;
    }

    static boolean isScheduledDate(Emi emi, LocalDate dueDate) {
        LocalDate first = firstDueDate(emi);
        if (dueDate.isBefore(first) || !dueDate.equals(dueDate(emi, YearMonth.from(dueDate)))) {
            return false;
        }
        if (!emi.isDurationKnown()) {
            return true;
        }
        long monthIndex = java.time.temporal.ChronoUnit.MONTHS.between(
                YearMonth.from(first), YearMonth.from(dueDate));
        return emi.getDurationMonths() != null
                && monthIndex >= 0
                && monthIndex < emi.getDurationMonths();
    }

    static LocalDate dueDate(Emi emi, YearMonth period) {
        return period.atDay(Math.min(emi.getDueDateDay(), period.lengthOfMonth()));
    }
}
