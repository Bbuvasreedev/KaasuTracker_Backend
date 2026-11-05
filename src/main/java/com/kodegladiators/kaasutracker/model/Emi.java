package com.kodegladiators.kaasutracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "emis")
public class Emi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "creation_key", length = 100)
    private String creationKey;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date_day", nullable = false)
    private Integer dueDateDay;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Builder.Default
    @Column(name = "duration_known", nullable = false)
    private boolean durationKnown = false;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "total_installments")
    private Integer totalInstallments;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmiStatus status;

    @JsonIgnore
    @Column(name = "started_on", nullable = false)
    private LocalDate startedOn;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @PrePersist
    void setDefaultStartDate() {
        if (startedOn == null) {
            startedOn = LocalDate.now();
        }
        if (status == null) {
            status = isActive ? EmiStatus.ACTIVE : EmiStatus.INACTIVE;
        }
        isActive = status == EmiStatus.ACTIVE;
    }
}
