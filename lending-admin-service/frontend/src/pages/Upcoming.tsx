import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Kpi, Loading, PageHeader, Pager, StatusBadge } from '../components/ui';
import { count, date, downloadCsv, money } from '../format';
import { useLoad } from '../hooks';
import type { UpcomingResponse, UpcomingRow } from '../types';

const WINDOWS = [7, 15, 30, 60, 90];

export default function Upcoming() {
  const nav = useNavigate();
  const [days, setDays] = useState(30);
  const [product, setProduct] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useLoad(
    () => get<UpcomingResponse>('/upcoming-payments', { days, product, page, size: 20 }), [days, product, page]);

  const cols: Col<UpcomingRow>[] = [
    { key: 'd', header: 'Due date', render: r => date(r.dueDate) },
    { key: 'in', header: 'In', render: r => (r.daysToDue === 0 ? 'Today' : r.daysToDue < 0 ? `${-r.daysToDue}d ago` : `${r.daysToDue}d`) },
    { key: 'a', header: 'Account', render: r => <strong>{r.accountNumber}</strong> },
    { key: 'c', header: 'Customer', render: r => r.customerId },
    { key: 'p', header: 'Product', render: r => r.productCode },
    { key: 'n', header: 'Instalment', align: 'center', render: r => r.installmentNumber },
    { key: 'pr', header: 'Principal', align: 'right', render: r => money(r.principal) },
    { key: 'i', header: 'Interest', align: 'right', render: r => money(r.interest) },
    { key: 'e', header: 'EMI due', align: 'right', render: r => <strong>{money(r.emi)}</strong> },
    { key: 's', header: 'Status', render: r => <StatusBadge status={r.accountStatus} /> },
  ];

  function exportCsv() {
    if (!data) return;
    downloadCsv(`upcoming-payments-${data.from}-to-${data.to}.csv`,
      ['Due date', 'Account', 'Customer', 'Product', 'Instalment', 'Principal', 'Interest', 'EMI', 'Account status'],
      data.page.items.map(r => [r.dueDate, r.accountNumber, r.customerId, r.productCode, r.installmentNumber, r.principal, r.interest, r.emi, r.accountStatus]));
  }

  return (
    <>
      <PageHeader title="Upcoming payments" sub="Instalments falling due, across all live accounts" />
      <Card
        actions={
          <>
            <select value={days} onChange={e => { setDays(Number(e.target.value)); setPage(0); }} aria-label="Window">
              {WINDOWS.map(w => <option key={w} value={w}>Next {w} days</option>)}
            </select>
            <input placeholder="Product code" size={10} value={product} onChange={e => { setProduct(e.target.value.toUpperCase()); setPage(0); }} aria-label="Product code" />
            <button className="btn" onClick={exportCsv} disabled={!data}>Export this page (CSV)</button>
          </>
        }
      >
        {loading && !data && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && (
          <>
            <div className="kpis">
              <Kpi title="Expected collections" value={money(data.totalAmount)} sub={`${date(data.from)} to ${date(data.to)}`} />
              <Kpi title="Instalments due" value={count(data.page.total)} />
            </div>
            <DataTable columns={cols} rows={data.page.items} rowKey={r => `${r.accountId}-${r.installmentNumber}`}
              onRowClick={r => nav(`/accounts/${r.accountId}`)} empty="Nothing falls due in this window" />
            <Pager page={data.page} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
