import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import { DailyArea } from '../components/charts';
import DataTable, { Col } from '../components/DataTable';
import { Card, Empty, ErrorBox, Loading, PageHeader, Pager } from '../components/ui';
import { count, date, downloadCsv, money, shortDate } from '../format';
import { useLoad } from '../hooks';
import type { AccrualRow, DailyAmount, Page } from '../types';

export default function Accruals() {
  const nav = useNavigate();
  const summary = useLoad(() => get<DailyAmount[]>('/accruals/summary'), []);
  const [day, setDay] = useState('');
  const [page, setPage] = useState(0);

  // Default to the most recent accrual date once the summary is in.
  useEffect(() => {
    if (!day && summary.data && summary.data.length) setDay(summary.data[summary.data.length - 1].date);
  }, [summary.data, day]);

  const rows = useLoad(() => (day ? get<Page<AccrualRow>>('/accruals', { date: day, page, size: 20 }) : Promise.resolve(null)), [day, page]);

  const cols: Col<AccrualRow>[] = [
    { key: 'a', header: 'Account', render: r => <strong>{r.accountNumber}</strong> },
    { key: 'o', header: 'Outstanding principal', align: 'right', render: r => money(r.outstandingPrincipal) },
    { key: 'i', header: 'Interest accrued', align: 'right', render: r => <strong>{money(r.accruedAmount)}</strong> },
  ];

  const sumCols: Col<DailyAmount>[] = [
    { key: 'd', header: 'Business date', render: r => date(r.date) },
    { key: 'n', header: 'Accounts accrued', align: 'right', render: r => count(r.count) },
    { key: 'a', header: 'Total interest', align: 'right', render: r => <strong>{money(r.amount)}</strong> },
  ];

  return (
    <>
      <PageHeader title="Interest accruals" sub="Output of the daily accrual run: totals per business date, then account by account" />
      {summary.loading && !summary.data && <Loading />}
      {summary.error && <ErrorBox message={summary.error} onRetry={summary.reload} />}
      {summary.data && summary.data.length === 0 && <Empty>No accruals in the last 30 days. Run POST /api/v1/interest-accruals/run on the lending service to create them.</Empty>}
      {summary.data && summary.data.length > 0 && (
        <>
          <Card title="Total interest accrued per day, last 30 days">
            <DailyArea name="Total interest accrued per day" data={summary.data} />
          </Card>
          <div className="grid-2">
            <Card title="Runs">
              <DataTable columns={sumCols} rows={[...summary.data].reverse()} rowKey={r => r.date}
                onRowClick={r => { setDay(r.date); setPage(0); }} rowClass={r => (r.date === day ? 'row-selected' : undefined)} />
            </Card>
            <Card
              title={day ? `Accounts accrued on ${shortDate(day)}` : 'Accounts'}
              actions={
                <>
                  <input type="date" value={day} onChange={e => { setDay(e.target.value); setPage(0); }} aria-label="Business date" />
                  <button className="btn" disabled={!rows.data} onClick={() => rows.data && downloadCsv(`accruals-${day}.csv`, ['Account', 'Outstanding principal', 'Interest accrued'], rows.data.items.map(r => [r.accountNumber, r.outstandingPrincipal, r.accruedAmount]))}>Export page (CSV)</button>
                </>
              }
            >
              {rows.loading && !rows.data && <Loading />}
              {rows.error && <ErrorBox message={rows.error} onRetry={rows.reload} />}
              {rows.data && (
                <>
                  <DataTable columns={cols} rows={rows.data.items} rowKey={r => r.accountId} onRowClick={r => nav(`/accounts/${r.accountId}`)} empty="No accruals on this date" />
                  <Pager page={rows.data} onPage={setPage} />
                </>
              )}
            </Card>
          </div>
        </>
      )}
    </>
  );
}
