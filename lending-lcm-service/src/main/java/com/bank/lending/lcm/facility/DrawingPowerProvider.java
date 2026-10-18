package com.bank.lending.lcm.facility;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Implemented by the collateral module; keeps the facility package free of a dependency on it. */
public interface DrawingPowerProvider {
    BigDecimal drawingPower(long facilityId, LocalDate asOf);
}
