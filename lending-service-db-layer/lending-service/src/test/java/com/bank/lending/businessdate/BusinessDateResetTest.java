package com.bank.lending.businessdate;

import com.bank.lending.businessdate.domain.BusinessDateHistory;
import com.bank.lending.businessdate.domain.BusinessDateState;
import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.businessdate.dto.BusinessDateResponse;
import com.bank.lending.businessdate.repository.BusinessDateHistoryRepository;
import com.bank.lending.businessdate.repository.BusinessDateStateRepository;
import com.bank.lending.businessdate.repository.NonWorkingDayRepository;
import com.bank.lending.businessdate.service.BusinessDateService;
import com.bank.lending.common.partition.PartitionMaintenanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring-free: the start-from-scratch reset of the business date. */
class BusinessDateResetTest {

    private final BusinessDateStateRepository stateRepository = Mockito.mock(BusinessDateStateRepository.class);
    private final BusinessDateHistoryRepository historyRepository = Mockito.mock(BusinessDateHistoryRepository.class);
    private final NonWorkingDayRepository holidayRepository = Mockito.mock(NonWorkingDayRepository.class);
    private final PartitionMaintenanceService partitions = Mockito.mock(PartitionMaintenanceService.class);

    private final BusinessDateService service = new BusinessDateService(stateRepository, historyRepository,
            holidayRepository, partitions, "SATURDAY,SUNDAY", true, false, "Asia/Kolkata");

    private BusinessDateState state(LocalDate date, BusinessDateStatus status) {
        BusinessDateState s = new BusinessDateState();
        s.setId(BusinessDateState.SINGLETON_ID);
        s.setBusinessDate(date);
        s.setPreviousDate(date.minusDays(1));
        s.setStatus(status);
        s.setUpdatedBy("system");
        s.setVersion(0);
        return s;
    }

    @Test
    void aDateFarInTheFutureIsBroughtBackToTodayAndOpened() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        BusinessDateState s = state(today.plusDays(400), BusinessDateStatus.OPEN);
        when(stateRepository.findForUpdate()).thenReturn(Optional.of(s));

        BusinessDateResponse r = service.resetToToday("kiran");

        assertThat(r.businessDate()).isEqualTo(today);
        assertThat(r.status()).isEqualTo(BusinessDateStatus.OPEN);
        assertThat(r.previousBusinessDate()).isNull();
        assertThat(s.getBusinessDate()).isEqualTo(today);
        assertThat(s.getUpdatedBy()).isEqualTo("kiran");
        verify(stateRepository).save(s);
    }

    @Test
    void theHistoryRestartsWithTodayOnlyAndThePartitionExists() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        BusinessDateState s = state(today.minusDays(30), BusinessDateStatus.OPEN);
        when(stateRepository.findForUpdate()).thenReturn(Optional.of(s));

        service.resetToToday("kiran");

        InOrder order = inOrder(historyRepository, partitions);
        order.verify(historyRepository).deleteAllInBatch();
        ArgumentCaptor<BusinessDateHistory> saved = ArgumentCaptor.forClass(BusinessDateHistory.class);
        order.verify(historyRepository).save(saved.capture());
        order.verify(partitions).ensurePartitions(today);
        assertThat(saved.getValue().getBusinessDate()).isEqualTo(today);
        assertThat(saved.getValue().getNote()).contains("kiran").contains(today.minusDays(30).toString());
    }

    @Test
    void aStuckEndOfDayStatusIsClearedToo() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        BusinessDateState s = state(today, BusinessDateStatus.EOD_RUNNING);
        when(stateRepository.findForUpdate()).thenReturn(Optional.of(s));

        BusinessDateResponse r = service.resetToToday(null);

        assertThat(r.status()).isEqualTo(BusinessDateStatus.OPEN);
        assertThat(s.getUpdatedBy()).isEqualTo("system");
    }
}
