import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { LimitsGate, StateBadge } from '../components/limits';
import { Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { Collateral } from '../types';

export default function LimitsCollateral() {
  return (
    <>
      <PageHeader title="Collateral" sub="Security held against facilities: valuation, legal checks, charge and insurance"
        actions={<Link className="btn btn-primary" to="/limits/collateral/new">New collateral</Link>} />
      <LimitsGate><List /></LimitsGate>
    </>
  );
}

function List() {
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const customerId = sp.get('customerId') ?? '';
  const status = sp.get('status') ?? '';
  const [text, setText] = useState(customerId);
  const [bad, setBad] = useState<string | null>(null);
  const { data, error, loading, reload } = useLoad(() => get<Collateral[]>('/limits/collateral', { customerId, status }), [customerId, status]);

  function update(next: Record<string, string>) {
    const n = new URLSearchParams(sp);
    Object.entries(next).forEach(([k, v]) => (v === '' ? n.delete(k) : n.set(k, v)));
    setSp(n);
  }
  function submit(e: FormEvent) {
    e.preventDefault();
    if (text.trim() !== '' && !/^\d+$/.test(text.trim())) { setBad('Customer id must be a whole number'); return; }
    setBad(null);
    update({ customerId: text.trim() });
  }

  const cols: Col<Collateral>[] = [
    { key: 'ref', header: 'Collateral', render: r => <strong>{r.collateralRef}</strong> },
    { key: 'cust', header: 'Customer', render: r => r.customerId },
    { key: 'type', header: 'Type', render: r => <>{label(r.collateralType)}<div className="muted">{r.description}</div></> },
    { key: 'st', header: 'Status', render: r => <StateBadge status={r.status} /> },
    { key: 'legal', header: 'Legal', render: r => <StateBadge status={r.legalStatus} /> },
    { key: 'val', header: 'Latest valuation', align: 'right', render: r => (r.latestValuation ? <>{money(r.latestValuation.marketValue)}<div className="muted">{date(r.latestValuation.valuationDate)} ({label(r.latestValuation.status)})</div></> : '-') },
    { key: 'lend', header: 'Lendable value', align: 'right', render: r => money(r.lendableValue) },
    { key: 'gap', header: 'Still needed', render: r => (r.status === 'DRAFT' ? (r.unmetForActivation.length ? `${r.unmetForActivation.length} item(s)` : 'Ready to activate') : '') },
  ];

  return (
    <Card>
      <form className="filters" onSubmit={submit}>
        <input placeholder="Customer id" inputMode="numeric" value={text} onChange={e => setText(e.target.value)} aria-label="Customer id" />
        <select value={status} onChange={e => update({ status: e.target.value })} aria-label="Status">
          <option value="">All statuses</option>
          {['DRAFT', 'ACTIVE', 'RELEASED'].map(s => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <button className="btn btn-primary">Search</button>
        <button type="button" className="btn" onClick={() => { setText(''); setBad(null); setSp(new URLSearchParams()); }}>Clear</button>
      </form>
      {bad && <div className="banner banner-bad" role="alert">{bad}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && <DataTable columns={cols} rows={data} rowKey={r => r.collateralRef} onRowClick={r => nav(`/limits/collateral/${encodeURIComponent(r.collateralRef)}`)} empty="No collateral matches. Register one with New collateral." />}
    </Card>
  );
}
