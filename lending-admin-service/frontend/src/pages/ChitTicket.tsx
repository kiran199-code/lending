import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { get } from '../api';
import { ChitBadge, ChitGate, useCanChangeChit } from '../components/chit';
import {
  DemandBadge, PaymentsTable, ReceiptModal, RecordPaymentDialog, ReversePaymentDialog, overdueText,
} from '../components/chitCollection';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, NoticeBanner, useActingAs } from '../components/limits';
import { Notice } from '../components/ops';
import { Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { ChitDemand, ChitLateFeePreview, ChitPayment, ChitStatement } from '../types';

export default function ChitTicket() {
  const { code = '', no = '' } = useParams();
  return (
    <>
      <PageHeader title={<>Ticket <span className="mono">{code} #{no}</span></>}
        sub={<><Link to={`/chit/groups/${encodeURIComponent(code)}`}>Group {code}</Link> · <Link to="/chit/dues">All dues</Link></>} />
      <ChitGate><Body code={code} no={Number(no)} /></ChitGate>
    </>
  );
}

function Body({ code, no }: { code: string; no: number }) {
  const canChange = useCanChangeChit();
  const actor = useActingAs();
  const [tick, setTick] = useState(0);
  const [notice, setNotice] = useState<Notice>(null);
  const [paying, setPaying] = useState(false);
  const [reversing, setReversing] = useState<ChitPayment | null>(null);
  const [receipt, setReceipt] = useState<ChitPayment | null>(null);
  const [days, setDays] = useState(30);
  const [showFees, setShowFees] = useState(false);

  const base = `/chit/groups/${encodeURIComponent(code)}/tickets/${no}`;
  const st = useLoad(() => get<ChitStatement>(`${base}/statement`), [code, no, tick]);
  const s = st.data;

  if (!Number.isInteger(no) || no < 1) return <ErrorBox message="That is not a ticket number" />;
  if (st.loading && !s) return <Loading what="Loading the ticket account (the chit service may be waking up)" />;
  if (st.error) return <ErrorBox message={st.error} onRetry={st.reload} />;
  if (!s) return null;

  const open = s.demands.filter(d => d.status === 'OPEN');
  const c = s.openDefaultCase;
  const refresh = () => setTick(t => t + 1);
  const replayed = (p: ChitPayment) => (p.replayed ? ' (the same request had already been applied; nothing new was done)' : '');

  const demandCols: Col<ChitDemand>[] = [
    { key: 'cy', header: 'Cycle', align: 'right', render: d => d.cycleNo },
    { key: 'due', header: 'Due date', render: d => date(d.dueDate) },
    { key: 'sub', header: 'Instalment', align: 'right', render: d => money(d.subscriptionAmount - d.dividendAdjustment) },
    { key: 'paid', header: 'Paid', align: 'right', render: d => money(d.subscriptionPaid) },
    { key: 'fee', header: 'Late fee', align: 'right', render: d => (d.lateFeeAccrued > 0 ? `${money(d.lateFeePaid)} of ${money(d.lateFeeAccrued)}` : '-') },
    { key: 'out', header: 'Outstanding', align: 'right', render: d => (d.totalOutstanding > 0 ? <strong>{money(d.totalOutstanding)}</strong> : '-') },
    { key: 'od', header: 'Overdue', render: d => (d.status === 'OPEN' ? overdueText(d.daysOverdue) : '-') },
    { key: 'st', header: 'Status', render: d => <DemandBadge status={d.status} /> },
  ];

  return (
    <>
      <ActingAsBar needed={false} />
      <NoticeBanner notice={notice} />

      {c && (
        <div className="banner banner-bad" role="alert">
          <strong>Defaulted since {date(c.openedOn)}.</strong>{' '}
          {c.reason ?? 'Too many unpaid instalments'}. Shortfall {money(c.shortfall)}
          {c.missedInstalments !== null ? `, ${c.missedInstalments} missed instalment(s)` : ''}{c.daysOverdue !== null ? `, oldest ${c.daysOverdue} days overdue` : ''}.
          {' '}The flag clears by itself when a payment covers everything overdue.
        </div>
      )}

      <Card title={<>Account <ChitBadge status={s.ticketStatus} /></>}
        actions={canChange ? <button className="btn btn-primary" onClick={() => setPaying(true)}>Record payment</button> : undefined}>
        <div className="kpis">
          <Kpi title="Total outstanding" value={money(s.totalOutstanding)} sub={`as of ${date(s.asOf)}`} tone={s.totalOutstanding > 0 && open.some(d => d.daysOverdue > 0) ? 'bad' : undefined} />
          <Kpi title="Instalments" value={money(s.subscriptionOutstanding)} />
          <Kpi title="Late fees" value={money(s.lateFeeOutstanding)} tone={s.lateFeeOutstanding > 0 ? 'warn' : undefined} />
          <Kpi title="Advance held" value={money(s.advanceHeld)} sub="used for the next instalments" />
          <Kpi title="Paid so far" value={money(s.totalPaid)} />
          <Kpi title="Next due" value={date(s.nextDueDate)} sub={`Customer ${s.customerId}`} />
        </div>
      </Card>

      <Card title={`Instalments (${s.demands.length})`} actions={<button className="btn" onClick={refresh}>Refresh</button>}>
        <DataTable columns={demandCols} rows={s.demands} rowKey={d => d.demandRef}
          rowClass={d => (d.status === 'OPEN' && d.daysOverdue > 0 ? 'row-bad' : undefined)}
          empty="No instalment has been raised for this ticket yet. Run the demand job from Dues & payments once a cycle falls due." />
      </Card>

      <Card title={`Payments (${s.payments.length})`}>
        <PaymentsTable rows={s.payments} canChange={canChange} onReceipt={setReceipt} onReverse={setReversing} empty="No payments recorded for this ticket." />
      </Card>

      {open.some(d => d.daysOverdue > 0) && (
        <Card title="What the late fee will come to"
          actions={(
            <span className="filters" style={{ margin: 0 }}>
              <select value={days} onChange={e => setDays(Number(e.target.value))} aria-label="Days ahead">
                {[7, 15, 30, 60, 90].map(d => <option key={d} value={d}>Next {d} days</option>)}
              </select>
              <button className="btn" onClick={() => setShowFees(true)}>Show</button>
            </span>)}>
          {showFees ? <FeePreview path={`${base}/late-fee-preview`} days={days} /> : <p className="muted" style={{ margin: 0 }}>Shows what each overdue instalment would be charged over the coming days if nothing is paid.</p>}
        </Card>
      )}

      {paying && (
        <RecordPaymentDialog code={code} ticketNo={no} customerId={s.customerId} suggested={s.totalOutstanding} actor={actor}
          onDone={p => {
            setPaying(false);
            setReceipt(p);
            setNotice({ tone: 'good', text: `Payment ${p.paymentRef} of ${money(p.amount)} recorded: ${money(p.allocated)} applied to dues`
              + `${p.advance > 0 ? `, ${money(p.advance)} held as advance` : ''}${replayed(p)}.` });
            refresh();
          }}
          onClose={() => setPaying(false)} />
      )}
      {reversing && (
        <ReversePaymentDialog p={reversing} actor={actor}
          onDone={r => { setReversing(null); setNotice({ tone: 'good', text: `Payment ${r.paymentRef} reversed${replayed(r)}.` }); refresh(); }}
          onClose={() => setReversing(null)} />
      )}
      {receipt && <ReceiptModal code={code} paymentRef={receipt.paymentRef} onClose={() => setReceipt(null)} />}
    </>
  );
}

/** The total late fee on the demand at the end of the projection (the service's running figure already includes what is accrued). */
const projected = (d: ChitLateFeePreview['demands'][number]) => (d.days.length ? d.days[d.days.length - 1].cumulative : d.accruedSoFar);

function FeePreview({ path, days }: { path: string; days: number }) {
  const { data, error, loading, reload } = useLoad(() => get<ChitLateFeePreview>(path, { days }), [path, days]);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  return (
    <>
      <p className="muted" style={{ marginTop: 0 }}>{label(data.rule)}. From {date(data.from)} for {data.days} days, if nothing is paid.</p>
      <DataTable columns={[
        { key: 'cy', header: 'Cycle', align: 'right', render: d => d.cycleNo },
        { key: 'due', header: 'Due date', render: d => date(d.dueDate) },
        { key: 'ov', header: 'Overdue amount', align: 'right', render: d => money(d.overdue) },
        { key: 'so', header: 'Charged so far', align: 'right', render: d => money(d.accruedSoFar) },
        { key: 'more', header: `Further fee over ${data.days} days`, align: 'right', render: d => money(projected(d) - d.accruedSoFar) },
        { key: 'tot', header: 'Late fee by then', align: 'right', render: d => <strong>{money(projected(d))}</strong> },
      ]} rows={data.demands} rowKey={d => d.demandRef} empty="Nothing overdue, so no late fee is coming." />
    </>
  );
}
