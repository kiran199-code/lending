package com.bank.lending.businessdate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The single row (id = 1) holding the platform's current business date. */
@Entity
@Table(name = "business_date_state")
@Getter
@Setter
@NoArgsConstructor
public class BusinessDateState {

    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Short id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "previous_date")
    private LocalDate previousDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private BusinessDateStatus status;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
