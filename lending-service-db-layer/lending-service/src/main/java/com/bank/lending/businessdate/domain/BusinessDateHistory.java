package com.bank.lending.businessdate.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "business_date_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessDateHistory {

    @Id
    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "eod_started_at")
    private OffsetDateTime eodStartedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "note", length = 200)
    private String note;
}
