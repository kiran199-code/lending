package com.bank.lending.e2e.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DpdModelTest {

    private final DpdModel model = new DpdModel("ACTIVE:0,NPA_SMA0:30,NPA_SMA1:60,NPA_SMA2:90,NPA_SUBSTANDARD:365,NPA_DOUBTFUL:1095,NPA_LOSS:max");

    @Test
    void dpdIsAgeOfOldestUnpaidDueInstalment() {
        LocalDate asOf = LocalDate.parse("2026-12-31");
        assertEquals(0, DpdModel.dpd(asOf, List.of()));
        assertEquals(0, DpdModel.dpd(asOf, List.of(LocalDate.parse("2026-12-31")))); // due today is not yet past due
        assertEquals(30, DpdModel.dpd(asOf, List.of(LocalDate.parse("2026-12-01"), LocalDate.parse("2027-01-01"))));
        assertEquals(60, DpdModel.dpd(asOf, List.of(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"))));
    }

    @Test
    void bucketBoundariesAreInclusive() {
        assertEquals("ACTIVE", model.classify(0));
        assertEquals("NPA_SMA0", model.classify(1));
        assertEquals("NPA_SMA0", model.classify(30));
        assertEquals("NPA_SMA1", model.classify(31));
        assertEquals("NPA_SMA2", model.classify(90));
        assertEquals("NPA_SUBSTANDARD", model.classify(91));
        assertEquals("NPA_SUBSTANDARD", model.classify(365));
        assertEquals("NPA_DOUBTFUL", model.classify(366));
        assertEquals("NPA_LOSS", model.classify(1096));
        assertEquals("NPA_LOSS", model.classify(100000));
    }

    @Test
    void thresholdsComeFromConfiguration() {
        DpdModel custom = new DpdModel("CURRENT:0,WATCH:15,NPA:max");
        assertEquals("WATCH", custom.classify(15));
        assertEquals("NPA", custom.classify(16));
        assertTrue(custom.rank("NPA") > custom.rank("WATCH"));
    }
}
