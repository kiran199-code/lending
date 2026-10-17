import { FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Loading, PageHeader, Pager, StatusBadge } from '../components/ui';
import { date, money, pct } from '../format';
import { useLoad } from '../hooks';
import type { AccountSummary, Page } from '../types';

const STATUSES = ['PENDING_DISBURSEMENT', 'ACTIVE', 'NPA_SMA0', 'NPA_SMA1', 'NPA_SMA2', 'NPA_SUBSTANDARD', 'NPA_DOUBTFUL', 'NPA_LOSS', 'CLOSED', 'FORECLOSED', 'WRITTEN_OFF'];

export default function Accounts() {
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const q = sp.get('q') ?? '';
  const status = sp.get('status') ?? '';
  const type = sp.get('type') ?? '';
  const product = sp.get('product') ?? '';
  const sort = sp.get('sort') ?? 'createdAt';
  const dir = sp.get('dir') ?? 'desc';
  const pageNo = Number(sp.get('page') ?? 0);
  const [text, setText] = useState(q);

  const { data, error, loading, reload } = useLoad(
    () => get<Page<AccountSummary>>('/accounts', { q, status, type, product, sort, dir, page: pageNo, size: 20 }),
    [q, status, type, product, sort, dir, pageNo],
  );

  function update(next: Record<string, string | number>) {
    const n = new URLSearchParams(sp);
    Object.entries(next).forEach(([k, v]) => (v === '' ? n.delete(k) : n.set(k, String(v))));
    setSp(n);
  }
  function submit(e: FormEvent) { e.preventDefault(); update({ q: text.trim(), page: 0 }); }
  function onSort(key: string) {
    update({ sort: key, dir: sort === key && dir === 'asc' ? 'desc' : 'asc', page: 0 });
  }

  const cols: Col<AccountSummary>[] = [
    { key: 'no', header: 'Account number', sortKey: 'accountNumber', render: r => <strong>{r.accountNumber}</strong> },
    { key: 'cust', header: 'Customer', sortKey: 'customerId', render: r => r.customerId },
    { key: 'prod', header: 'Product', sortKey: 'product', render: r => `${r.productCode} (${r.accountType === 'MORTGAGE' ? 'Mortgage' : 'Unsecured'})` },
    { key: 'br', header: 'Branch', render: r => `${r.branchCode} (${r.currencyCode})` },
    { key: 'status', header: 'Status', sortKey: 'status', render: r => <StatusBadge status={r.accountStatus} /> },
    { key: 'sanc', header: 'Sanctioned', sortKey: 'sanctionedAmount', align: 'right', render: r => money(r.sanctionedAmount) },
    { key: 'rate', header: 'Rate', align: 'right', render: r => pct(r.interestRate) },
    { key: 'emi', header: 'EMI', align: 'right', render: r => money(r.emiAmount) },
    { key: 'next', header: 'Next due', sortKey: 'nextDue', render: r => (r.nextDueDate ? <>{date(r.nextDueDate)}{r.daysPastDue > 0 && <span className="overdue-note"> {r.daysPastDue}d late</span>}</> : '-') },
    { key: 'created', header: 'Created', sortKey: 'createdAt', render: r => date(r.createdAt) },
  ];

  return (
    <>
      <PageHeader title="Accounts" sub="Search by account number, customer id, sanction reference or account id" />
      <Card>
        <form className="filters" onSubmit={submit}>
          <input className="grow" placeholder="Search..." value={text} onChange={e => setText(e.target.value)} aria-label="Search accounts" />
          <select value={status} onChange={e => update({ status: e.target.value, page: 0 })} aria-label="Status">
            <option value="">All statuses</option>
            {STATUSES.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
          </select>
          <select value={type} onChange={e => update({ type: e.target.value, page: 0 })} aria-label="Type">
            <option value="">All types</option>
            <option value="UNSECURED">Unsecured</option>
            <option value="MORTGAGE">Mortgage</option>
          </select>
          <input placeholder="Product code" size={10} value={product} onChange={e => update({ product: e.target.value.toUpperCase(), page: 0 })} aria-label="Product code" />
          <button className="btn btn-primary">Search</button>
        </form>
        {loading && !data && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && (
          <>
            <DataTable columns={cols} rows={data.items} rowKey={r => r.accountId} onRowClick={r => nav(`/accounts/${r.accountId}`)}
              sort={sort} dir={dir} onSort={onSort} empty="No accounts match these filters" />
            <Pager page={data} onPage={p => update({ page: p })} />
          </>
        )}
      </Card>
    </>
  );
}
