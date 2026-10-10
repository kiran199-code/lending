import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import { DailyArea, DailyBars, StatusBars } from '../components/charts';
import { Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { compactMoney, count, date } from '../format';
import { useLoad } from '../hooks';
import type { Dashboard as Dash } from '../types';

export default function Dashboard() {
  const { data, error, loading, reload } = useLoad(() => get<Dash>('/dashboard'), []);
  const [q, setQ] = useState('');
  const nav = useNavigate();

  function search(e: FormEvent) {
    e.preventDefault();
    nav(`/accounts?q=${encodeURIComponent(q.trim())}`);
  }

  return (
    <>
      <PageHeader
        title="Dashboard"
        sub={data ? `As of ${date(data.asOf)}` : undefined}
        actions={
          <form className="inline-form" onSubmit={search}>
            <input placeholder="Find account number, customer id..." value={q} onChange={e => setQ(e.target.value)} aria-label="Find account" />
            <button className="btn btn-primary">Search</button>
          </form>
        }
      />
      {loading && !data && <Loading what="Loading dashboard" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <div className="kpis">
            <Kpi title="Accounts" value={count(data.totalAccounts)} sub={`${count(data.activeAccounts)} live`} />
            <Kpi title="Disbursed" value={compactMoney(data.totalDisbursed)} />
            <Kpi title="Outstanding principal" value={compactMoney(data.outstandingPrincipal)} />
            <Kpi title="Overdue" value={compactMoney(data.overdueAmount)} sub={`${count(data.overdueAccounts)} accounts`} tone={data.overdueAccounts > 0 ? 'bad' : undefined} />
            <Kpi title="Due in next 7 days" value={compactMoney(data.dueNext7Amount)} sub={`${count(data.dueNext7Count)} instalments`} />
            <Kpi title="Collected this month" value={compactMoney(data.collectionsMtd)} sub={`${count(data.collectionsMtdCount)} payments`} tone="good" />
            <Kpi title="Last accrual run" value={compactMoney(data.lastAccrualAmount)} sub={data.lastAccrualDate ? date(data.lastAccrualDate) : 'No accrual yet'} />
            <Kpi title="Ledger outbox" value={`${count(data.outboxPending)} pending`} sub={`${count(data.outboxFailed)} failed`} tone={data.outboxFailed > 0 ? 'warn' : undefined} />
          </div>
          <div className="grid-2">
            <Card title="Accounts by status"><StatusBars data={data.byStatus} /></Card>
            <Card title="Daily interest accrued, last 30 days">
              {data.accrualSeries.length ? <DailyArea data={data.accrualSeries} name="Daily interest accrued" /> : <div className="state">No accruals in this period. Run the accrual job to see them here.</div>}
            </Card>
          </div>
          <Card title="Collections received, last 30 days">
            {data.collectionSeries.length ? <DailyBars data={data.collectionSeries} name="Daily collections" /> : <div className="state">No successful payments in this period.</div>}
          </Card>
        </>
      )}
    </>
  );
}
