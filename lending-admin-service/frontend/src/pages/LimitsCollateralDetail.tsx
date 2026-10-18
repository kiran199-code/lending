import { FormEvent, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, useActingAs, LimitsGate, NoticeBanner, StateBadge, UNMET_TEXT, useAction } from '../components/limits';
import { ConfirmModal, Field } from '../components/ops';
import { Card, Empty, ErrorBox, KeyValues, Kpi, Loading, PageHeader, Tabs } from '../components/ui';
import { date, dateTime, label, money, todayIso } from '../format';
import { useLoad } from '../hooks';
import type { Collateral, CollateralAllocation, Valuation } from '../types';

type TabId = 'overview' | 'valuations' | 'allocations';

export default function LimitsCollateralDetail() {
  return <LimitsGate><Body /></LimitsGate>;
}

function Body() {
  const { ref = '' } = useParams();
  const [tab, setTab] = useState<TabId>('overview');
  const col = useLoad(() => get<Collateral>(`/limits/collateral/${encodeURIComponent(ref)}`), [ref]);
  if (col.loading && !col.data) return <Loading what="Loading collateral" />;
  if (col.error) return <ErrorBox message={col.error} onRetry={col.reload} />;
  const c = col.data;
  if (!c) return null;
  const lv = c.latestValuation;
  return (
    <>
      <p className="crumb"><Link to="/limits/collateral">Collateral</Link> / {c.collateralRef}</p>
      <PageHeader title={<>{c.collateralRef} <StateBadge status={c.status} /></>}
        sub={`Customer ${c.customerId} | ${label(c.collateralType)} | ${c.currencyCode}`}
        actions={<button className="btn" onClick={col.reload}>Refresh</button>} />
      <div className="kpis">
        <Kpi title="Latest valuation" value={lv ? money(lv.marketValue) : '-'} sub={lv ? `${date(lv.valuationDate)}, ${label(lv.status).toLowerCase()}` : 'None proposed yet'} />
        <Kpi title="Eligible value" value={money(c.eligibleValue)} sub={`after ${c.haircutPct}% haircut and prior charge`} />
        <Kpi title="Lendable value" value={money(c.lendableValue)} sub={`at ${c.maxLtvPct}% maximum LTV`} />
        <Kpi title="Legal verification" value={label(c.legalStatus)} tone={c.legalStatus === 'VERIFIED' ? 'good' : c.legalStatus === 'FAILED' ? 'bad' : 'warn'} />
      </div>
      <Tabs<TabId> tabs={[{ id: 'overview', label: 'Overview and checks' }, { id: 'valuations', label: 'Valuations' }, { id: 'allocations', label: 'Allocation to facilities' }]} value={tab} onChange={setTab} />
      {tab === 'overview' && <Overview c={c} onChanged={col.reload} />}
      {tab === 'valuations' && <Valuations c={c} onChanged={col.reload} />}
      {tab === 'allocations' && <Allocations c={c} />}
    </>
  );
}

// ------------------------------------------------------------------ overview: legal, charge, insurance, lifecycle

function Overview({ c, onChanged }: { c: Collateral; onChanged: () => void }) {
  const act = useAction();
  const base = `/limits/collateral/${encodeURIComponent(c.collateralRef)}`;
  const [legal, setLegal] = useState(c.legalStatus);
  const [chargeRef, setChargeRef] = useState(c.chargeRef ?? '');
  const [chargeOn, setChargeOn] = useState(c.chargeRegisteredOn ?? '');
  const [prior, setPrior] = useState(c.priorChargeAmount === null ? '' : String(c.priorChargeAmount));
  const [expiry, setExpiry] = useState(c.insuranceExpiry ?? '');
  const [confirm, setConfirm] = useState<'activate' | 'release' | null>(null);
  const closed = c.status === 'RELEASED';

  const run = <T,>(fn: () => Promise<T>, ok: string) => act.run(fn, () => ok, onChanged);

  function saveCharge(e: FormEvent) {
    e.preventDefault();
    if (prior !== '' && !(Number(prior) >= 0)) { act.setNotice({ tone: 'bad', text: 'Prior charge cannot be negative' }); return; }
    const body: Record<string, unknown> = {};
    if (chargeRef.trim()) body.chargeRef = chargeRef.trim();
    if (chargeOn) body.registeredOn = chargeOn;
    if (prior !== '') body.priorChargeAmount = Number(prior);
    void run(() => send('POST', `${base}/charge`, body), 'Charge details saved.');
  }
  function saveInsurance(e: FormEvent) {
    e.preventDefault();
    if (!expiry) { act.setNotice({ tone: 'bad', text: 'Choose the insurance expiry date' }); return; }
    void run(() => send('POST', `${base}/insurance`, { expiry }), 'Insurance expiry saved.');
  }

  return (
    <>
      <ActingAsBar needed={false} />
      <NoticeBanner notice={act.notice} />
      {c.status === 'DRAFT' && (
        <Card title="Before it can be activated" actions={<button className="btn btn-primary" disabled={act.busy || c.unmetForActivation.length > 0} onClick={() => setConfirm('activate')}>Activate</button>}>
          {c.unmetForActivation.length === 0
            ? <p style={{ margin: 0 }}>Everything required is in place. Activate to let it count towards drawing power.</p>
            : <ul style={{ margin: 0 }}>{c.unmetForActivation.map(u => <li key={u}>{UNMET_TEXT[u] ?? label(u)}</li>)}</ul>}
        </Card>
      )}
      <div className="grid-2">
        <Card title="Details">
          <KeyValues items={[
            ['Reference', c.collateralRef], ['Customer', c.customerId], ['Type', label(c.collateralType)], ['Description', c.description],
            ['Haircut', `${c.haircutPct}%`], ['Maximum LTV', `${c.maxLtvPct}%`],
            ['Charge', c.chargeRegistered ? `Registered ${date(c.chargeRegisteredOn)}${c.chargeRef ? ` (${c.chargeRef})` : ''}` : 'Not registered'],
            ['Prior charge', c.priorChargeAmount === null ? '-' : money(c.priorChargeAmount)],
            ['Insurance expires', date(c.insuranceExpiry)],
            ['Bridged from lending mortgage', c.lendingMortgageId ?? '-'], ['Source', c.source ?? '-'],
          ]} />
        </Card>
        <div>
          <Card title="Legal verification">
            <div className="inline-form">
              <select value={legal} disabled={closed} onChange={e => setLegal(e.target.value)} aria-label="Legal verification">
                {['PENDING', 'VERIFIED', 'FAILED'].map(s => <option key={s} value={s}>{label(s)}</option>)}
              </select>
              <button className="btn btn-primary" disabled={act.busy || closed || legal === c.legalStatus}
                onClick={() => run(() => send('POST', `${base}/legal-verification`, { status: legal }), `Legal verification recorded as ${label(legal).toLowerCase()}.`)}>Record</button>
            </div>
          </Card>
          <Card title="Charge registration">
            <form onSubmit={saveCharge}>
              <div className="form-grid">
                <Field label="Charge reference"><input value={chargeRef} disabled={closed} onChange={e => setChargeRef(e.target.value)} /></Field>
                <Field label="Registered on"><input type="date" value={chargeOn} disabled={closed} max={todayIso()} onChange={e => setChargeOn(e.target.value)} /></Field>
                <Field label="Prior charge amount"><input type="number" step="0.01" min="0" value={prior} disabled={closed} onChange={e => setPrior(e.target.value)} /></Field>
              </div>
              <button className="btn" disabled={act.busy || closed}>Save charge details</button>
            </form>
          </Card>
          <Card title="Insurance">
            <form onSubmit={saveInsurance} className="inline-form">
              <input type="date" value={expiry} disabled={closed} onChange={e => setExpiry(e.target.value)} aria-label="Insurance expiry" />
              <button className="btn" disabled={act.busy || closed}>Save expiry</button>
            </form>
          </Card>
        </div>
      </div>
      {c.status === 'ACTIVE' && (
        <Card title="Release">
          <p className="muted">Releasing returns the security to the customer. It is refused while any allocation to a facility is still active.</p>
          <button className="btn btn-danger" disabled={act.busy} onClick={() => setConfirm('release')}>Release collateral</button>
        </Card>
      )}
      {confirm && (
        <ConfirmModal title={confirm === 'activate' ? 'Activate this collateral?' : 'Release this collateral?'} danger={confirm === 'release'}
          rows={[['Collateral', c.collateralRef], ['Customer', c.customerId], ['Lendable value', money(c.lendableValue)]]}
          confirmLabel={confirm === 'activate' ? 'Activate' : 'Release'} typeToConfirm={confirm === 'release' ? 'RELEASE' : undefined}
          onClose={() => setConfirm(null)}
          onConfirm={async () => {
            await send('POST', `${base}/${confirm}`);
            setConfirm(null);
            act.setNotice({ tone: 'good', text: confirm === 'activate' ? 'Collateral activated.' : 'Collateral released.' });
            onChanged();
          }} />
      )}
    </>
  );
}

// ------------------------------------------------------------------ valuations (maker-checker)

function Valuations({ c, onChanged }: { c: Collateral; onChanged: () => void }) {
  const list = useLoad(() => get<Valuation[]>(`/limits/collateral/${encodeURIComponent(c.collateralRef)}/valuations`), [c.collateralRef]);
  const act = useAction();
  const [on, setOn] = useState(todayIso());
  const [market, setMarket] = useState('');
  const [forced, setForced] = useState('');
  const [valuer, setValuer] = useState('');
  const [error, setError] = useState<string | null>(null);
  const acting = useActingAs();

  async function propose(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!on) return setError('Choose the valuation date');
    if (!(Number(market) > 0)) return setError('Market value must be greater than zero');
    if (forced !== '' && !(Number(forced) > 0)) return setError('Forced-sale value must be greater than zero');
    const body: Record<string, unknown> = { valuationDate: on, marketValue: Number(market) };
    if (forced !== '') body.forcedSaleValue = Number(forced);
    if (valuer.trim()) body.valuerRef = valuer.trim();
    const ok = await act.run(() => send<Valuation>('POST', `/limits/collateral/${encodeURIComponent(c.collateralRef)}/valuations`, body),
      v => `Valuation ${v.id} proposed. A different person must approve it.`);
    if (ok) { setMarket(''); setForced(''); setValuer(''); list.reload(); }
  }

  async function decide(v: Valuation, approve: boolean) {
    await act.run(() => send('POST', `/limits/valuations/${v.id}/${approve ? 'approve' : 'reject'}`),
      () => `Valuation ${v.id} ${approve ? 'approved' : 'rejected'}.`, () => { list.reload(); onChanged(); });
  }

  const cols: Col<Valuation>[] = [
    { key: 'id', header: '#', render: r => r.id },
    { key: 'd', header: 'Date', render: r => date(r.valuationDate) },
    { key: 'm', header: 'Market value', align: 'right', render: r => <strong>{money(r.marketValue)}</strong> },
    { key: 'f', header: 'Forced sale', align: 'right', render: r => money(r.forcedSaleValue) },
    { key: 'v', header: 'Valuer', render: r => r.valuerRef ?? '-' },
    { key: 's', header: 'Status', render: r => <StateBadge status={r.status} /> },
    { key: 'mk', header: 'Proposed by', render: r => <>{r.maker}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'ck', header: 'Decided by', render: r => r.checker ?? '-' },
    { key: 'x', header: '', render: r => (r.status === 'PENDING' ? (
      <div className="filters">
        <button className="btn btn-primary" disabled={act.busy || r.maker === acting} title={r.maker === acting ? 'Proposed under this name; a different person must approve' : undefined} onClick={() => decide(r, true)}>Approve</button>
        <button className="btn" disabled={act.busy} onClick={() => decide(r, false)}>Reject</button>
      </div>
    ) : null) },
  ];

  return (
    <>
      <ActingAsBar />
      <NoticeBanner notice={act.notice} />
      {c.status !== 'RELEASED' && (
        <Card title="Propose a valuation">
          <form onSubmit={propose}>
            <div className="form-grid">
              <Field label="Valuation date"><input type="date" max={todayIso()} value={on} onChange={e => setOn(e.target.value)} /></Field>
              <Field label="Market value"><input type="number" step="0.01" min="0" value={market} onChange={e => setMarket(e.target.value)} /></Field>
              <Field label="Forced-sale value" hint="Optional"><input type="number" step="0.01" min="0" value={forced} onChange={e => setForced(e.target.value)} /></Field>
              <Field label="Valuer reference" hint="Optional"><input value={valuer} onChange={e => setValuer(e.target.value)} /></Field>
            </div>
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary" disabled={act.busy}>Propose valuation</button>
          </form>
        </Card>
      )}
      <Card title="Valuation history">
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty="No valuation yet" />}
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ allocations

function Allocations({ c }: { c: Collateral }) {
  const list = useLoad(() => get<CollateralAllocation[]>(`/limits/collateral/${encodeURIComponent(c.collateralRef)}/allocations`), [c.collateralRef]);
  const act = useAction();
  const [facility, setFacility] = useState('');
  const [pct, setPct] = useState('100');
  const [error, setError] = useState<string | null>(null);
  const used = (list.data ?? []).filter(a => a.status === 'ACTIVE').reduce((s, a) => s + a.allocationPct, 0);

  async function allocate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!/^[A-Za-z0-9_.:-]{1,64}$/.test(facility.trim())) return setError('Enter the facility reference');
    if (!(Number(pct) >= 0.01 && Number(pct) <= 100)) return setError('Share must be between 0.01 and 100');
    const ok = await act.run(() => send<CollateralAllocation>('POST', `/limits/collateral/${encodeURIComponent(c.collateralRef)}/allocations`,
      { facilityRef: facility.trim(), allocationPct: Number(pct) }), a => `${a.allocationPct}% allocated to ${a.facilityRef}.`);
    if (ok) { setFacility(''); list.reload(); }
  }

  async function releaseAllocation(a: CollateralAllocation) {
    await act.run(() => send('POST', `/limits/allocations/${a.id}/release`), () => `Allocation to ${a.facilityRef} released.`, list.reload);
  }

  const cols: Col<CollateralAllocation>[] = [
    { key: 'f', header: 'Facility', render: r => <Link to={`/limits/facilities/${encodeURIComponent(r.facilityRef)}`}>{r.facilityRef}</Link> },
    { key: 'p', header: 'Share of this collateral', align: 'right', render: r => `${r.allocationPct}%` },
    { key: 's', header: 'Status', render: r => <StateBadge status={r.status} /> },
    { key: 'b', header: 'Allocated by', render: r => <>{r.createdBy ?? '-'}<div className="muted">{dateTime(r.createdAt)}</div></> },
    { key: 'r', header: 'Released', render: r => dateTime(r.releasedAt) },
    { key: 'x', header: '', render: r => (r.status === 'ACTIVE' ? <button className="btn" disabled={act.busy} onClick={() => releaseAllocation(r)}>Release</button> : null) },
  ];

  return (
    <>
      <NoticeBanner notice={act.notice} />
      <Card title="Allocate to a facility">
        {c.status !== 'ACTIVE'
          ? <Empty>Only active collateral can be allocated. Activate it first.</Empty>
          : (
            <form onSubmit={allocate}>
              <p className="muted">Currently {used}% allocated. The allocations of one collateral cannot add up to more than 100%. Allocation raises the drawing power of a facility that is governed by it.</p>
              <div className="form-grid">
                <Field label="Facility reference"><input value={facility} onChange={e => setFacility(e.target.value)} /></Field>
                <Field label="Share %"><input type="number" step="0.01" min="0.01" max="100" value={pct} onChange={e => setPct(e.target.value)} /></Field>
              </div>
              {error && <div className="banner banner-bad" role="alert">{error}</div>}
              <button className="btn btn-primary" disabled={act.busy}>Allocate</button>
            </form>
          )}
      </Card>
      <Card title="Allocations">
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty="Not allocated to any facility" />}
      </Card>
    </>
  );
}
