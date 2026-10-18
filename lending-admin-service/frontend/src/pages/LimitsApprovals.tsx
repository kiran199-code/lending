import { useState } from 'react';
import { Link } from 'react-router-dom';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, useActingAs, DecisionModal, describeChange, LimitsGate, NoticeBanner, useAction } from '../components/limits';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { date, dateTime, money } from '../format';
import { Loaded, useLoad } from '../hooks';
import type { FacilityChange, FacilityExcess, Valuation } from '../types';

type Pending = { kind: 'change' | 'excess' | 'valuation'; id: number; approve: boolean; title: string; maker: string };

/** Everything waiting for a second person: facility changes, temporary excess and valuations. */
export default function LimitsApprovals() {
  return (
    <>
      <PageHeader title="Approvals" sub="Items proposed by one person that a different person must approve" />
      <LimitsGate><Body /></LimitsGate>
    </>
  );
}

function Body() {
  const changes = useLoad(() => get<FacilityChange[]>('/limits/changes', { status: 'PENDING' }), []);
  const excess = useLoad(() => get<FacilityExcess[]>('/limits/excess-requests', { status: 'PENDING' }), []);
  const vals = useLoad(() => get<Valuation[]>('/limits/valuations', { status: 'PENDING' }), []);
  const act = useAction();
  const [pending, setPending] = useState<Pending | null>(null);
  const [note, setNote] = useState('');
  const acting = useActingAs();

  function reload() { changes.reload(); excess.reload(); vals.reload(); }

  async function decide() {
    if (!pending) return;
    const p = pending;
    const path = p.kind === 'change' ? `/limits/changes/${p.id}` : p.kind === 'excess' ? `/limits/excess/${p.id}` : `/limits/valuations/${p.id}`;
    await send('POST', `${path}/${p.approve ? 'approve' : 'reject'}`, p.kind === 'change' ? { note: note.trim() || undefined } : undefined);
    setPending(null);
    setNote('');
    act.setNotice({ tone: 'good', text: `${p.title} ${p.approve ? 'approved' : 'rejected'}.` });
    reload();
  }

  const buttons = (kind: Pending['kind'], id: number, maker: string, title: string) => (
    <div className="filters">
      <button className="btn btn-primary" disabled={maker === acting}
        title={maker === acting ? 'Proposed under this name; a different person must approve' : undefined}
        onClick={() => { setPending({ kind, id, approve: true, title, maker }); setNote(''); }}>Approve</button>
      <button className="btn" onClick={() => { setPending({ kind, id, approve: false, title, maker }); setNote(''); }}>Reject</button>
    </div>
  );

  const changeCols: Col<FacilityChange>[] = [
    { key: 'f', header: 'Facility', render: r => <Link to={`/limits/facilities/${encodeURIComponent(r.facilityRef)}`}>{r.facilityRef}</Link> },
    { key: 't', header: 'Change', render: r => <>{describeChange(r.changeType, r.payload)}{r.reason && <div className="muted">{r.reason}</div>}</> },
    { key: 'm', header: 'Proposed by', render: r => <>{r.maker}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'x', header: '', render: r => buttons('change', r.id, r.maker, `Change ${r.id}`) },
  ];
  const excessCols: Col<FacilityExcess>[] = [
    { key: 'f', header: 'Facility', render: r => <Link to={`/limits/facilities/${encodeURIComponent(r.facilityRef)}`}>{r.facilityRef}</Link> },
    { key: 'a', header: 'Excess', align: 'right', render: r => <strong>{money(r.amount)}</strong> },
    { key: 'w', header: 'Valid', render: r => `${date(r.validFrom)} to ${date(r.validTo)}` },
    { key: 'r', header: 'Reason', render: r => r.reason },
    { key: 'm', header: 'Proposed by', render: r => <>{r.maker}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'x', header: '', render: r => buttons('excess', r.id, r.maker, `Excess ${r.id}`) },
  ];
  const valCols: Col<Valuation>[] = [
    { key: 'c', header: 'Collateral', render: r => <Link to={`/limits/collateral/${encodeURIComponent(r.collateralRef)}`}>{r.collateralRef}</Link> },
    { key: 'd', header: 'Valuation date', render: r => date(r.valuationDate) },
    { key: 'v', header: 'Market value', align: 'right', render: r => <strong>{money(r.marketValue)}</strong> },
    { key: 'f', header: 'Forced sale', align: 'right', render: r => money(r.forcedSaleValue) },
    { key: 'vr', header: 'Valuer', render: r => r.valuerRef ?? '-' },
    { key: 'm', header: 'Proposed by', render: r => <>{r.maker}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'x', header: '', render: r => buttons('valuation', r.id, r.maker, `Valuation ${r.id}`) },
  ];

  const section = <T,>(title: string, l: Loaded<T[]>, table: (rows: T[]) => JSX.Element) => (
    <Card title={<>{title} {l.data && <Badge tone={l.data.length ? 'warn' : 'neutral'}>{l.data.length}</Badge>}</>} actions={<button className="btn" onClick={l.reload}>Refresh</button>}>
      {l.loading && !l.data && <Loading />}
      {l.error && <ErrorBox message={l.error} onRetry={l.reload} />}
      {l.data && table(l.data)}
    </Card>
  );

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={act.notice} />
      {section('Facility changes', changes, rows => <DataTable columns={changeCols} rows={rows} rowKey={r => r.id} empty="Nothing waiting" />)}
      {section('Temporary excess', excess, rows => <DataTable columns={excessCols} rows={rows} rowKey={r => r.id} empty="Nothing waiting" />)}
      {section('Collateral valuations', vals, rows => <DataTable columns={valCols} rows={rows} rowKey={r => r.id} empty="Nothing waiting" />)}
      {pending && (
        <DecisionModal title={`${pending.approve ? 'Approve' : 'Reject'} ${pending.title.toLowerCase()}?`} confirmLabel={pending.approve ? 'Approve' : 'Reject'} danger={!pending.approve}
          rows={[['Item', pending.title], ['Proposed by', pending.maker], ['Deciding as', acting || '-']]}
          note={note} setNote={setNote} onConfirm={decide} onClose={() => setPending(null)} />
      )}
    </>
  );
}
