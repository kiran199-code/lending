import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Kpi, Loading, PageHeader, Pager, StatusBadge } from '../components/ui';
import { count, date, downloadCsv, money } from '../format';
import { useLoad } from '../hooks';
import type { Bucket, OverdueRow, Page } from '../types';

const BUCKETS: { id: string; label: string; min?: number; max?: number }[] = [
  { id: '1-30', label: '1 to 30 days', min: 1, max: 30 },
  { id: '31-60', label: '31 to 60 days', min: 31, max: 60 },
  { id: '61-90', label: '61 to 90 days', min: 61, max: 90 },
  { id: '90+', label: 'Over 90 days', min: 91 },
];

export default function Overdue() {
  const nav = useNavigate();
  const [bucket, setBucket] = useState<string>('');
  const [page, setPage] = useState(0);
  const b = BUCKETS.find(x => x.id === bucket);
  const buckets = useLoad(() => get<Bucket[]>('/overdue/buckets'), []);
  const rows = useLoad(() => get<Page<OverdueRow>>('/overdue', { minDpd: b?.min, maxDpd: b?.max, page, size: 20 }), [bucket, page]);

  const cols: Col<OverdueRow>[] = [
    { key: 'a', header: 'Account', render: r => <strong>{r.accountNumber}</strong> },
    { key: 'c', header: 'Customer', render: r => r.customerId },
    { key: 'p', header: 'Product', render: r => r.productCode },
    { key: 'n', header: 'Instalment', align: 'center', render: r => r.installmentNumber },
    { key: 'd', header: 'Due date', render: r => date(r.dueDate) },
    { key: 'dpd', header: 'Days past due', align: 'right', render: r => <span className="overdue-note">{r.daysPastDue}</span> },
    { key: 'e', header: 'EMI', align: 'right', render: r => <strong>{money(r.emi)}</strong> },
    { key: 's', header: 'Account status', render: r => <StatusBadge status={r.accountStatus} /> },
  ];

  function exportCsv() {
    if (!rows.data) return;
    downloadCsv('overdue-instalments.csv', ['Account', 'Customer', 'Product', 'Instalment', 'Due date', 'Days past due', 'EMI', 'Account status'],
      rows.data.items.map(r => [r.accountNumber, r.customerId, r.productCode, r.installmentNumber, r.dueDate, r.daysPastDue, r.emi, r.accountStatus]));
  }

  const find = (id: string) => buckets.data?.find(x => x.bucket === id);

  return (
    <>
      <PageHeader title="Overdue" sub="Unpaid instalments past their due date. Buckets count accounts by their oldest unpaid instalment." />
      {buckets.error && <ErrorBox message={buckets.error} onRetry={buckets.reload} />}
      <div className="kpis">
        {BUCKETS.map(x => {
          const s = find(x.id);
          return (
            <button key={x.id} className={`kpi kpi-button ${bucket === x.id ? 'kpi-selected' : ''}`} onClick={() => { setBucket(bucket === x.id ? '' : x.id); setPage(0); }} aria-pressed={bucket === x.id}>
              <div className="kpi-title">{x.label}</div>
              <div className="kpi-value">{count(s?.accounts ?? 0)} <span className="kpi-unit">accounts</span></div>
              <div className="kpi-sub">{money(s?.amount ?? 0)}</div>
            </button>
          );
        })}
        <Kpi title="All overdue" value={count((buckets.data ?? []).reduce((n, x) => n + x.accounts, 0))} sub={money((buckets.data ?? []).reduce((n, x) => n + x.amount, 0))} tone="bad" />
      </div>
      <Card title={b ? `Overdue instalments: ${b.label}` : 'Overdue instalments'} actions={<button className="btn" onClick={exportCsv} disabled={!rows.data}>Export this page (CSV)</button>}>
        {rows.loading && !rows.data && <Loading />}
        {rows.error && <ErrorBox message={rows.error} onRetry={rows.reload} />}
        {rows.data && (
          <>
            <DataTable columns={cols} rows={rows.data.items} rowKey={r => `${r.accountId}-${r.installmentNumber}`}
              onRowClick={r => nav(`/accounts/${r.accountId}`)} empty="No overdue instalments" />
            <Pager page={rows.data} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
