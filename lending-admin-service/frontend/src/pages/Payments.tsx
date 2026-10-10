import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Loading, PageHeader, Pager, PaymentBadge } from '../components/ui';
import { date, downloadCsv, label, money } from '../format';
import { useLoad } from '../hooks';
import type { Page, PaymentRow } from '../types';

export default function Payments() {
  const nav = useNavigate();
  const [f, setF] = useState({ from: '', to: '', status: '', type: '', channel: '', accountNumber: '' });
  const [page, setPage] = useState(0);
  const set = (k: keyof typeof f, v: string) => { setF({ ...f, [k]: v }); setPage(0); };
  const { data, error, loading, reload } = useLoad(() => get<Page<PaymentRow>>('/payments', { ...f, page, size: 20 }), [f, page]);

  const cols: Col<PaymentRow>[] = [
    { key: 'd', header: 'Value date', render: r => date(r.valueDate) },
    { key: 'a', header: 'Account', render: r => <strong>{r.accountNumber}</strong> },
    { key: 't', header: 'Type', render: r => label(r.type) },
    { key: 'n', header: 'Instalment', align: 'center', render: r => r.installmentNumber ?? '-' },
    { key: 'am', header: 'Amount', align: 'right', render: r => <strong>{money(r.amount)}</strong> },
    { key: 'p', header: 'Principal', align: 'right', render: r => money(r.principalPaid) },
    { key: 'i', header: 'Interest', align: 'right', render: r => money(r.interestPaid) },
    { key: 'c', header: 'Channel', render: r => label(r.channel) },
    { key: 's', header: 'Status', render: r => <PaymentBadge status={r.status} /> },
  ];

  function exportCsv() {
    if (!data) return;
    downloadCsv('payments.csv', ['Transaction', 'Value date', 'Account', 'Type', 'Instalment', 'Amount', 'Principal', 'Interest', 'Channel', 'Status'],
      data.items.map(r => [r.transactionId, r.valueDate, r.accountNumber, r.type, r.installmentNumber, r.amount, r.principalPaid, r.interestPaid, r.channel, r.status]));
  }

  return (
    <>
      <PageHeader title="Payments" sub="Every payment attempt across all accounts, newest first" />
      <Card actions={<button className="btn" onClick={exportCsv} disabled={!data}>Export this page (CSV)</button>}>
        <div className="filters">
          <label className="inline-label">From <input type="date" value={f.from} onChange={e => set('from', e.target.value)} /></label>
          <label className="inline-label">To <input type="date" value={f.to} onChange={e => set('to', e.target.value)} /></label>
          <input placeholder="Account number" value={f.accountNumber} onChange={e => set('accountNumber', e.target.value)} aria-label="Account number" />
          <select value={f.status} onChange={e => set('status', e.target.value)} aria-label="Status">
            <option value="">All statuses</option><option value="SUCCESS">Success</option><option value="PENDING">Pending</option><option value="FAILED">Failed</option>
          </select>
          <select value={f.type} onChange={e => set('type', e.target.value)} aria-label="Type">
            <option value="">All types</option><option value="EMI">EMI</option><option value="PART_PAYMENT">Part payment</option><option value="FORECLOSURE">Foreclosure</option><option value="CHARGE">Charge</option>
          </select>
          <select value={f.channel} onChange={e => set('channel', e.target.value)} aria-label="Channel">
            <option value="">All channels</option><option value="NACH">NACH</option><option value="MANUAL">Manual</option><option value="ONLINE">Online</option>
          </select>
        </div>
        {loading && !data && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && (
          <>
            <DataTable columns={cols} rows={data.items} rowKey={r => r.transactionId} onRowClick={r => nav(`/accounts/${r.accountId}`)} empty="No payments match these filters" />
            <Pager page={data} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
