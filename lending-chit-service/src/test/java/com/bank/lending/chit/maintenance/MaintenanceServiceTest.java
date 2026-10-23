package com.bank.lending.chit.maintenance;

import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The two refusals happen before the database is touched, so no database is needed (the template is null on purpose). */
class MaintenanceServiceTest {

    @Test
    void clearingIsRefusedWhileTheSwitchIsOff() {
        MaintenanceService off = new MaintenanceService(null, false);
        ChitException e = assertThrows(ChitException.class, () -> off.clearAll("RESET", "admin"));
        assertEquals("RESET_DISABLED", e.code());
    }

    @Test
    void clearingNeedsTheConfirmationWord() {
        MaintenanceService on = new MaintenanceService(null, true);
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll(null, "admin")).code());
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll("reset", "admin")).code());
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll("yes", "admin")).code());
    }
}
