import { FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import {
  ActingAsBar, useActingAs, AvailabilityLine, changeTypesFor, DecisionModal, describeChange, FacilityBadge, LimitsGate, NoticeBanner, StateBadge, useAction,
} from '../components/limits';
import { Field } from '../components/ops';
import { Card, Empty, ErrorBox, KeyValues, Kpi, Loading, PageHeader, Tabs } from '../components/ui';
import { date, dateTime, label, money, todayIso } from '../format';
import { useLoad } from '../hooks';
import type { Availability, Facility, FacilityChange, FacilityExcess, FacilityTree, LimitTxn, DrawingPower } from '../types';

type TabId = 'overview' | 'changes' | 'excess' | 'ledger' | 'dp';

export default function LimitsFacilityDetail() {
  return <LimitsGate><Body /></LimitsGate>;
}

function Body() {
  const { ref = '' } = useParams();
  const [tab, setTab] = useState<TabId>('overview');
  const fac = useLoad(() => get<Facility>(`/limits/facilities/${encodeURIComponent(ref)}`), [ref]);
  const av = useLoad(() => get<Availability>(`/limits/facilities/${encodeURIComponent(ref)}/availability`), [ref]);

  function reloadAll() { fac.reload(); av.reload(); }

  if (fac.loading && !fac.data) return <Loading what="Loading facility" />;
  if (fac.error) return <ErrorBox message={fac.error} onRetry={fac.reload} />;
  const f = fac.data;
  if (!f) return null;
  const a = av.data;

  const tabs: { id: TabId; label: string }[] = [
    { id: 'overview', label: 'Overview' }, { id: 'changes', label: 'Changes' }, { id: 'excess', label: 'Temporary excess' },
    { id: 'ledger', label: 'Ledger' }, ...(f.dpGoverned ? [{ id: 'dp' as TabId, label: 'Drawing power' }] : []),
  ];

  return (
    <>
      <p className="crumb"><Link to="/limits/facilities">Facilities</Link> / {f.facilityRef}</p>
      <PageHeader title={<>{f.facilityRef} <FacilityBadge status={f.status} /></>}
        sub={`Customer ${f.customerId} | ${label(f.facilityType)}${f.productCode ? ` ${f.productCode}` : ''} | ${f.currencyCode}${f.parentRef ? ` | inside ${f.parentRef}` : ''}`}
        actions={<button className="btn" onClick={reloadAll}>Refresh</button>} />
      {a && (
        <div className="kpis">
          <Kpi title="Sanctioned" value={money(a.sanctionedAmount)} sub={a.activeExcess > 0 ? `plus ${money(a.activeExcess)} temporary excess` : undefined} />
          <Kpi title="Effective limit" value={money(a.effectiveLimit)} sub={a.dpGoverned ? `drawing power ${money(a.drawingPower)}` : undefined} />
          <Kpi title="Used" value={money(a.consumed)} sub={`utilised ${money(a.utilised)}, reserved ${money(a.reserved)}`} tone={a.overLimit ? 'bad' : undefined} />
          <Kpi title="Available now" value={money(a.effectiveAvailable)} sub={a.status === 'ACTIVE' ? 'after every parent limit' : `no drawdown while ${label(a.status).toLowerCase()}`} tone={a.effectiveAvailable > 0 ? 'good' : 'warn'} />
        </div>
      )}
      <Tabs tabs={tabs} value={tab} onChange={setTab} />
      {tab === 'overview' && <Overview f={f} />}
      {tab === 'changes' && <Changes f={f} onChanged={reloadAll} />}
      {tab === 'excess' && <Excess f={f} onChanged={reloadAll} />}
      {tab === 'ledger' && <Ledger refName={f.facilityRef} />}
      {tab === 'dp' && <Dp refName={f.facilityRef} />}
    </>
  );
}

// ------------------------------------------------------------------ overview

function Overview({ f }: { f: Facility }) {
  const tree = useLoad(() => get<FacilityTree>(`/limits/facilities/${encodeURIComponent(f.facilityRef)}/tree`), [f.facilityRef]);
  return (
    <div className="grid-2">
      <Card title="Terms">
        <KeyValues items={[
          ['Reference', f.facilityRef], ['Customer', f.customerId], ['Type', label(f.facilityType)], ['Parent', f.parentRef ? <Link to={`/limits/facilities/${encodeURIComponent(f.parentRef)}`}>{f.parentRef}</Link> : '-'],
          ['Product', f.productCode ?? '-'], ['Currency / branch', `${f.currencyCode} / ${f.branchCode ?? '-'}`],
          ['Revolving', f.revolving ? 'Yes' : 'No'], ['Governed by drawing power', f.dpGoverned ? 'Yes' : 'No'],
          ['Largest single drawdown', f.maxSingleAmount === null ? 'No cap' : money(f.maxSingleAmount)],
          ['Effective from', date(f.effectiveFrom)], ['Expires', date(f.expiryDate)],
          ['Sanction reference', f.sanctionRefId ?? '-'], ['Created by', f.createdBy ?? '-'], ['Activated by', f.approvedBy ?? '-'],
          ['Source', f.source ?? '-'],
        ]} />
      </Card>
      <Card title="Hierarchy and availability">
        {tree.loading && !tree.data && <Loading />}
        {tree.error && <ErrorBox message={tree.error} onRetry={tree.reload} />}
        {tree.data && <TreeView node={tree.data} depth={0} current={f.facilityRef} />}
        <p className="muted">Loans draw on a PRODUCT limit and are also counted against every limit above it.</p>
      </Card>
    </div>
  );
}

function TreeView({ node, depth, current }: { node: FacilityTree; depth: number; current: string }) {
  const a = node.facility;
  return (
    <div style={{ marginLeft: depth * 18, marginBottom: 8 }}>
      <div>
        {a.facilityRef === current ? <strong>{a.facilityRef}</strong> : <Link to={`/limits/facilities/${encodeURIComponent(a.facilityRef)}`}>{a.facilityRef}</Link>}
        {' '}<FacilityBadge status={a.status} />
      </div>
      <AvailabilityLine a={a} />
      {node.children.map(c => <TreeView key={c.facility.facilityRef} node={c} depth={depth + 1} current={current} />)}
    </div>
  );
}

// ------------------------------------------------------------------ changes (maker-checker)

function Changes({ f, onChanged }: { f: Facility; onChanged: () => void }) {
  const list = useLoad(() => get<FacilityChange[]>('/limits/changes', { facilityRef: f.facilityRef }), [f.facilityRef]);
  const act = useAction();
  const types = changeTypesFor(f.status);
  const [type, setType] = useState(types[0] ?? '');
  const [amount, setAmount] = useState('');
  const [expiry, setExpiry] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [deciding, setDeciding] = useState<{ c: FacilityChange; approve: boolean } | null>(null);
  const [note, setNote] = useState('');
  const effType = types.includes(type) ? type : (types[0] ?? '');

  async function propose(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const body: Record<string, unknown> = { changeType: effType, reason: reason.trim() || undefined };
    if (effType === 'AMEND_LIMIT') {
      if (amount === '' || !(Number(amount) >= 0)) return setError('Enter the new limit (zero or more)');
      body.newAmount = Number(amount);
    }
    if (effType === 'EXTEND_EXPIRY') {
      if (!expiry) return setError('Choose the new expiry date');
      if (f.expiryDate && expiry <= f.expiryDate) return setError(`The new expiry must be after the current ${f.expiryDate}`);
      body.newExpiry = expiry;
    }
    const ok = await act.run(() => send<FacilityChange>('POST', `/limits/facilities/${encodeURIComponent(f.facilityRef)}/changes`, body),
      c => `${describeChange(c.changeType, c.payload)} proposed (change ${c.id}). A different person must approve it under Limits > Approvals.`);
    if (ok) { setAmount(''); setExpiry(''); setReason(''); list.reload(); }
  }

  async function decide() {
    if (!deciding) return;
    const { c, approve } = deciding;
    await send('POST', `/limits/changes/${c.id}/${approve ? 'approve' : 'reject'}`, { note: note.trim() || undefined });
    setDeciding(null);
    setNote('');
    act.setNotice({ tone: 'good', text: `Change ${c.id} ${approve ? 'approved and applied' : 'rejected'}.` });
    list.reload();
    onChanged();
  }

  const acting = useActingAs();
  const cols: Col<FacilityChange>[] = [
    { key: 'id', header: '#', render: r => r.id },
    { key: 't', header: 'Change', render: r => <>{describeChange(r.changeType, r.payload)}{r.reason && <div className="muted">{r.reason}</div>}</> },
    { key: 's', header: 'Status', render: r => <StateBadge status={r.status} /> },
    { key: 'm', header: 'Proposed by', render: r => <>{r.maker}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'c', header: 'Decided by', render: r => (r.checker ? <>{r.checker}<div className="muted">{dateTime(r.decidedAt)}{r.decisionNote ? ` - ${r.decisionNote}` : ''}</div></> : '-') },
    { key: 'x', header: '', render: r => (r.status === 'PENDING' ? (
      <div className="filters">
        <button className="btn btn-primary" disabled={r.maker === acting} title={r.maker === acting ? 'Proposed under this name; a different person must approve' : undefined}
          onClick={() => { setDeciding({ c: r, approve: true }); setNote(''); }}>Approve</button>
        <button className="btn" onClick={() => { setDeciding({ c: r, approve: false }); setNote(''); }}>Reject</button>
      </div>
    ) : null) },
  ];

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={act.notice} />
      <Card title="Propose a change">
        {types.length === 0 ? <Empty>A {label(f.status).toLowerCase()} facility cannot be changed.</Empty> : (
          <form onSubmit={propose}>
            <div className="form-grid">
              <Field label="Change">
                <select value={effType} onChange={e => setType(e.target.value)}>
                  {types.map(t => <option key={t} value={t}>{label(t)}</option>)}
                </select>
              </Field>
              {effType === 'AMEND_LIMIT' && <Field label="New limit" hint={`Now ${money(f.sanctionedAmount)}. Cannot be below its sub-limits or above its parent.`}><input type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></Field>}
              {effType === 'EXTEND_EXPIRY' && <Field label="New expiry" hint={`Now ${date(f.expiryDate)}`}><input type="date" min={todayIso()} value={expiry} onChange={e => setExpiry(e.target.value)} /></Field>}
              <Field label="Reason" wide><input value={reason} onChange={e => setReason(e.target.value)} placeholder="Why (recorded with the change)" /></Field>
            </div>
            {effType === 'ACTIVATE' && f.parentRef && <p className="muted">The parent facility {f.parentRef} must already be active.</p>}
            {effType === 'CLOSE' && <p className="muted">Closing needs nothing utilised or reserved and every sub-limit closed first.</p>}
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary" disabled={act.busy}>{act.busy ? 'Sending...' : 'Propose change'}</button>
          </form>
        )}
      </Card>
      <Card title="History">
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty="No changes proposed yet" />}
      </Card>
      {deciding && (
        <DecisionModal title={`${deciding.approve ? 'Approve' : 'Reject'} change ${deciding.c.id}?`} confirmLabel={deciding.approve ? 'Approve' : 'Reject'} danger={!deciding.approve}
          rows={[['Facility', deciding.c.facilityRef], ['Change', describeChange(deciding.c.changeType, deciding.c.payload)], ['Proposed by', deciding.c.maker], ['Deciding as', acting || '-']]}
          note={note} setNote={setNote} onConfirm={decide} onClose={() => setDeciding(null)} />
      )}
    </>
  );
}

// ------------------------------------------------------------------ temporary excess

function Excess({ f, onChanged }: { f: Facility; onChanged: () => void }) {
  const list = useLoad(() => get<FacilityExcess[]>(`/limits/facilities/${encodeURIComponent(f.facilityRef)}/excess`), [f.facilityRef]);
  const act = useAction();
  const [amount, setAmount] = useState('');
  const [from, setFrom] = useState(todayIso());
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const acting = useActingAs();

  async function propose(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!(Number(amount) > 0)) return setError('Excess amount must be greater than zero');
    if (!from || !to) return setError('Choose the first and last day');
    if (to < from) return setError('The last day cannot be before the first day');
    if (reason.trim() === '') return setError('A reason is required');
    const ok = await act.run(() => send<FacilityExcess>('POST', `/limits/facilities/${encodeURIComponent(f.facilityRef)}/excess`,
      { amount: Number(amount), validFrom: from, validTo: to, reason: reason.trim() }),
      x => `Temporary excess ${x.id} proposed. A different person must approve it.`);
    if (ok) { setAmount(''); setTo(''); setReason(''); list.reload(); }
  }

  async function decide(x: FacilityExcess, approve: boolean) {
    await act.run(() => send('POST', `/limits/excess/${x.id}/${approve ? 'approve' : 'reject'}`),
      () => `Excess ${x.id} ${approve ? 'approved' : 'rejected'}.`, () => { list.reload(); onChanged(); });
  }

  const cols: Col<FacilityExcess>[] = [
    { key: 'id', header: '#', render: r => r.id },
    { key: 'a', header: 'Amount', align: 'right', render: r => <strong>{money(r.amount)}</strong> },
    { key: 'w', header: 'Valid', render: r => `${date(r.validFrom)} to ${date(r.validTo)}` },
    { key: 'r', header: 'Reason', render: r => r.reason },
    { key: 's', header: 'Status', render: r => <StateBadge status={r.status} /> },
    { key: 'm', header: 'Proposed by', render: r => r.maker },
    { key: 'c', header: 'Decided by', render: r => r.checker ?? '-' },
    { key: 'x', header: '', render: r => (r.status === 'PENDING' ? (
      <div className="filters">
        <button className="btn btn-primary" disabled={act.busy || r.maker === acting} onClick={() => decide(r, true)}>Approve</button>
        <button className="btn" disabled={act.busy} onClick={() => decide(r, false)}>Reject</button>
      </div>
    ) : null) },
  ];

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={act.notice} />
      <Card title="Propose a temporary excess">
        <p className="muted">Lets the facility go above its sanctioned limit between two dates. It adds to the limit only while approved and inside its dates.</p>
        <form onSubmit={propose}>
          <div className="form-grid">
            <Field label="Excess amount"><input type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} /></Field>
            <Field label="First day"><input type="date" value={from} onChange={e => setFrom(e.target.value)} /></Field>
            <Field label="Last day"><input type="date" value={to} onChange={e => setTo(e.target.value)} /></Field>
            <Field label="Reason" wide><input value={reason} onChange={e => setReason(e.target.value)} /></Field>
          </div>
          {error && <div className="banner banner-bad" role="alert">{error}</div>}
          <button className="btn btn-primary" disabled={act.busy}>Propose excess</button>
        </form>
      </Card>
      <Card title="History">
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty="No temporary excess recorded" />}
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ ledger and drawing power

function Ledger({ refName }: { refName: string }) {
  const { data, error, loading, reload } = useLoad(() => get<LimitTxn[]>(`/limits/facilities/${encodeURIComponent(refName)}/transactions`, { limit: 200 }), [refName]);
  const cols: Col<LimitTxn>[] = [
    { key: 'id', header: '#', render: r => r.id },
    { key: 'at', header: 'When', render: r => dateTime(r.createdAt) },
    { key: 't', header: 'Movement', render: r => label(r.txnType) },
    { key: 'o', header: 'Counted from', render: r => (r.originFacilityRef && r.originFacilityRef !== r.facilityRef ? r.originFacilityRef : 'This facility') },
    { key: 'a', header: 'Amount', align: 'right', render: r => money(r.amount) },
    { key: 'ur', header: 'Reserved change', align: 'right', render: r => money(r.dReserved) },
    { key: 'uu', header: 'Utilised change', align: 'right', render: r => money(r.dUtilised) },
    { key: 'ref', header: 'Reference', render: r => (r.referenceType ? `${label(r.referenceType)} ${r.referenceId ?? ''}` : '-') },
    { key: 'ac', header: 'By', render: r => r.actor ?? '-' },
  ];
  return (
    <Card title="Limit movements, newest first">
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && <DataTable columns={cols} rows={data} rowKey={r => r.id} empty="No movements yet" />}
    </Card>
  );
}

function Dp({ refName }: { refName: string }) {
  const { data, error, loading, reload } = useLoad(() => get<DrawingPower>(`/limits/facilities/${encodeURIComponent(refName)}/drawing-power`), [refName]);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  return (
    <Card title="Drawing power">
      <p><strong>{money(data.drawingPower)}</strong> <span className="muted">as of {date(data.asOf)}: the sum of what each allocated collateral can support</span></p>
      <DataTable
        columns={[
          { key: 'c', header: 'Collateral', render: r => <Link to={`/limits/collateral/${encodeURIComponent(r.collateralRef)}`}>{r.collateralRef}</Link> },
          { key: 'p', header: 'Share', align: 'right', render: r => `${r.allocationPct}%` },
          { key: 'b', header: 'Value used', align: 'right', render: r => money(r.baseValue) },
          { key: 'h', header: 'Haircut', align: 'right', render: r => `${r.haircutPct}%` },
          { key: 'pc', header: 'Prior charge', align: 'right', render: r => money(r.priorCharge) },
          { key: 'e', header: 'Eligible', align: 'right', render: r => money(r.eligibleValue) },
          { key: 'l', header: 'Max LTV', align: 'right', render: r => `${r.maxLtvPct}%` },
          { key: 'c2', header: 'Counts', align: 'right', render: r => <strong>{money(r.contribution)}</strong> },
          { key: 'z', header: 'Why zero', render: r => (r.zeroReason ? label(r.zeroReason) : '') },
        ]}
        rows={data.contributions} rowKey={r => r.collateralRef} empty="No active collateral is allocated to this facility, so the drawing power is zero" />
    </Card>
  );
}
