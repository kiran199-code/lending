import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { FacilityBadge, LimitsGate } from '../components/limits';
import { Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { Facility } from '../types';

const STATUSES = ['DRAFT', 'ACTIVE', 'SUSPENDED', 'FROZEN', 'EXPIRED', 'CLOSED'];

export default function LimitsFacilities() {
  return (
    <>
      <PageHeader title="Facilities" sub="Credit limits: overall limits, sub-limits and product limits"
        actions={<Link className="btn btn-primary" to="/limits/facilities/new">New facility</Link>} />
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

  const { data, error, loading, reload } = useLoad(
    () => get<Facility[]>('/limits/facilities', { customerId, status }), [customerId, status]);

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

  const cols: Col<Facility>[] = [
    { key: 'ref', header: 'Facility', render: r => <strong>{r.facilityRef}</strong> },
    { key: 'cust', header: 'Customer', render: r => r.customerId },
    { key: 'type', header: 'Type', render: r => <>{label(r.facilityType)}{r.productCode ? ` (${r.productCode})` : ''}</> },
    { key: 'parent', header: 'Parent', render: r => r.parentRef ?? '-' },
    { key: 'status', header: 'Status', render: r => <FacilityBadge status={r.status} /> },
    { key: 'lim', header: 'Sanctioned', align: 'right', render: r => `${money(r.sanctionedAmount)} ${r.currencyCode}` },
    { key: 'used', header: 'Utilised', align: 'right', render: r => money(r.utilised) },
    { key: 'res', header: 'Reserved', align: 'right', render: r => money(r.reserved) },
    { key: 'exp', header: 'Expires', render: r => date(r.expiryDate) },
  ];

  return (
    <Card>
      <form className="filters" onSubmit={submit}>
        <input placeholder="Customer id" inputMode="numeric" value={text} onChange={e => setText(e.target.value)} aria-label="Customer id" />
        <select value={status} onChange={e => update({ status: e.target.value })} aria-label="Status">
          <option value="">All statuses</option>
          {STATUSES.map(s => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <button className="btn btn-primary">Search</button>
        <button type="button" className="btn" onClick={() => { setText(''); setBad(null); setSp(new URLSearchParams()); }}>Clear</button>
      </form>
      {bad && <div className="banner banner-bad" role="alert">{bad}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <DataTable columns={cols} rows={data} rowKey={r => r.facilityRef} onRowClick={r => nav(`/limits/facilities/${encodeURIComponent(r.facilityRef)}`)}
          empty="No facilities match. Create one with New facility." />
      )}
    </Card>
  );
}
