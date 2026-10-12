package com.bank.lending.businessdate.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** A bank holiday (weekends are configured, not stored). */
@Entity
@Table(name = "non_working_day")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NonWorkingDay {

    @Id
    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(name = "description", nullable = false, length = 100)
    private String description;
}
