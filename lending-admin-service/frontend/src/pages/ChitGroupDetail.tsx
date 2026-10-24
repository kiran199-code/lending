import { ReactNode, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { get, send } from '../api';
import { ChitBadge, ChitGate, GroupBadge, useCanChangeChit, wholeNumber } from '../components/chit';
import { GroupCollectionCard, PaymentsTable, ReceiptModal, ReversePaymentDialog } from '../components/chitCollection';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, NoticeBanner, useActingAs } from '../components/limits';
import { ConfirmModal, Field, Notice, useIdempotencyKey } from '../components/ops';
import { Card, ErrorBox, KeyValues, Loading, PageHeader } from '../components/ui';
import { date, dateTime, label, money } from '../format';
import { useLoad } from '../hooks';
import type {
  ChitActivation, ChitCancelResult, ChitCycle, ChitGroup, ChitPayment, ChitQuote, ChitReadiness, ChitTicket, ChitWithdrawResult,
} from '../types';

/** Statuses before activation: tickets can still be enrolled, withdrawn or have their security verified, and the group cancelled. */
const PRE_ACTIVE = ['DRAFT', 'OPEN_FOR_ENROLMENT', 'FULL'];

type Dialog =
  | { kind: 'open' }
  | { kind: 'activate' }
  | { kind: 'cancel' }
  | { kind: 'withdraw'; ticket: ChitTicket }
  | { kind: 'verify'; ticket: ChitTicket }
  | { kind: 'enrol' }
  | null;

export default function ChitGroupDetail() {
  const { code = '' } = useParams();
  return (
    <>
      <PageHeader title={<>Chit group <span className="mono">{code}</span></>} sub={<Link to="/chit/groups">All groups</Link>} />
      <ChitGate><Body code={code} /></ChitGate>
    </>
  );
}

function Body({ code }: { code: string }) {
  const canChange = useCanChangeChit();
  const actor = useActingAs();
  const [tick, setTick] = useState(0);
  const [notice, setNotice] = useState<Notice>(null);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [reason, setReason] = useState('');
  const [receipt, setReceipt] = useState<ChitPayment | null>(null);
  const [reversing, setReversing] = useState<ChitPayment | null>(null);
  const keys = useIdempotencyKey();
  const path = `/chit/groups/${encodeURIComponent(code)}`;

  const group = useLoad(() => get<ChitGroup>(path), [code, tick]);
  const g = group.data;
  const tickets = useLoad(() => get<ChitTicket[]>(`${path}/tickets`, { includeExited: true }), [code, tick]);
  const preActive = !!g && PRE_ACTIVE.includes(g.status);
  const readiness = useLoad<ChitReadiness | null>(
    () => (preActive ? get<ChitReadiness>(`${path}/activation-check`) : Promise.resolve(null)), [code, tick, g?.status]);
  const quote = useLoad<ChitQuote | null>(
    () => (g?.status === 'OPEN_FOR_ENROLMENT' ? get<ChitQuote>(`${path}/enrolment-quote`) : Promise.resolve(null)), [code, tick, g?.status]);
  const cycles = useLoad(
    () => (g && !['DRAFT', 'CANCELLED'].includes(g.status) ? get<ChitCycle[]>(`${path}/cycles`) : Promise.resolve([] as ChitCycle[])),
    [code, tick, g?.status]);

  const collecting = !!g && ['ACTIVE', 'CLOSING', 'CLOSED'].includes(g.status);
  const payments = useLoad(
    () => (collecting ? get<ChitPayment[]>(`${path}/payments`) : Promise.resolve([] as ChitPayment[])), [code, tick, g?.status]);

  function done(text: string) {
    setNotice({ tone: 'good', text });
    setDialog(null);
    setReason('');
    setTick(t => t + 1);
  }

  /** One state-changing call. The same request keeps the same Idempotency-Key until it succeeds, so a retry is applied once. */
  async function post<T>(sub: string, body?: unknown): Promise<T> {
    const r = await send<T>('POST', `${path}${sub}`, body, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|${sub}|${JSON.stringify(body ?? null)}`) });
    keys.done();
    return r;
  }
  const replayed = (r: { replayed?: boolean | null }) => (r.replayed ? ' (the same request had already been applied; nothing new was done)' : '');

  if (group.loading && !g) return <Loading what="Loading group (the chit service may be waking up)" />;
  if (group.error) return <ErrorBox message={group.error} onRetry={group.reload} />;
  if (!g) return null;

  const ts = tickets.data ?? [];
  const live = ts.filter(t => t.status !== 'EXITED');

  const ticketCols: Col<ChitTicket>[] = [
    { key: 'no', header: 'Ticket', render: t => (collecting && t.status !== 'EXITED'
        ? <Link to={`/chit/groups/${encodeURIComponent(code)}/tickets/${t.ticketNo}`}><strong>{t.ticketNo}</strong></Link>
        : <strong>{t.ticketNo}</strong>) },
    { key: 'cust', header: 'Customer', render: t => t.customerId },
    { key: 'status', header: 'Status', render: t => <ChitBadge status={t.status} /> },
    { key: 'payout', header: 'Payout account', render: t => t.payoutAccountRef ?? '-' },
    {
      key: 'fees', header: 'Fees', render: t => (t.fees.length === 0 ? '-' : t.fees.map(f => (
        <div key={f.kind}>{label(f.kind)} {money(f.amount)} <ChitBadge status={f.status} /></div>))),
    },
    {
      key: 'sec', header: 'Security', render: t => (t.security
        ? <>{label(t.security.kind)} {money(t.security.amount)} <ChitBadge status={t.security.status} />
          {t.security.guarantorCustomerId ? <div className="muted">Guarantor {t.security.guarantorCustomerId}</div> : null}
          {t.security.reference ? <div className="muted">Ref {t.security.reference}</div> : null}
          {t.security.verifiedBy ? <div className="muted">Verified by {t.security.verifiedBy}</div> : null}</>
        : '-'),
    },
    {
      key: 'act', header: '', render: t => (canChange && preActive && t.status !== 'EXITED'
        ? (
          <span style={{ whiteSpace: 'nowrap' }}>
            {t.security && !t.security.verifiedBy && t.security.status !== 'VERIFIED' && (
              <button className="btn" onClick={() => setDialog({ kind: 'verify', ticket: t })}>Verify security</button>
            )}{' '}
            <button className="btn" onClick={() => { setReason(''); setDialog({ kind: 'withdraw', ticket: t }); }}>Withdraw</button>
          </span>)
        : t.status === 'EXITED' ? <span className="muted">{t.exitReason ?? 'Withdrawn'}{t.exitedOn ? ` on ${date(t.exitedOn)}` : ''}</span> : null),
    },
  ];

  const cycleCols: Col<ChitCycle>[] = [
    { key: 'no', header: 'Cycle', render: c => c.cycleNo },
    { key: 'status', header: 'Status', render: c => <ChitBadge status={c.status} /> },
    { key: 'due', header: 'Instalment due', render: c => date(c.dueDate) },
    { key: 'auction', header: 'Auction', render: c => date(c.auctionDate) },
  ];

  const r = readiness.data;

  return (
    <>
      <ActingAsBar needed={false} />
      <NoticeBanner notice={notice} />
      <Card title={<>{g.name} <GroupBadge status={g.status} /></>}
        actions={canChange && preActive ? (
          <>
            {g.status === 'DRAFT' && <button className="btn btn-primary" onClick={() => setDialog({ kind: 'open' })}>Open enrolment</button>}
            <button className="btn btn-danger" onClick={() => { setReason(''); setDialog({ kind: 'cancel' }); }}>Cancel group</button>
          </>) : undefined}>
        <KeyValues items={[
          ['Group code', g.groupCode], ['Foreman', g.foremanRef], ['Chit value', money(g.chitValue)], ['Subscription', `${money(g.subscription)} ${label(g.frequency).toLowerCase()}`],
          ['Tickets', `${g.ticketsEnrolled} of ${g.ticketCount} enrolled (${g.seatsLeft} left)`], ['Cycles', g.cycleCount], ['Starts', date(g.startDate)],
          ['Rules version', g.rulesVersion], ['Created', `${dateTime(g.createdAt)} by ${g.createdBy}`],
          ['Activated', g.activatedAt ? `${dateTime(g.activatedAt)} by ${g.activatedBy}` : '-'],
          ['Cancelled', g.cancelledAt ? `${dateTime(g.cancelledAt)} by ${g.cancelledBy}: ${g.cancelReason}` : '-'],
        ]} />
      </Card>

      {g.status === 'OPEN_FOR_ENROLMENT' && canChange && (
        <Card title="Enrol a ticket" actions={<button className="btn btn-primary" disabled={g.seatsLeft === 0} onClick={() => setDialog({ kind: 'enrol' })}>Enrol</button>}>
          {quote.loading && !quote.data && <Loading what="Reading the enrolment quote" />}
          {quote.error && <ErrorBox message={quote.error} onRetry={quote.reload} />}
          {quote.data && <QuoteLine q={quote.data} />}
        </Card>
      )}

      <Card title={`Tickets (${live.length} of ${g.ticketCount})`} actions={<button className="btn" onClick={() => setTick(t => t + 1)}>Refresh</button>}>
        {tickets.loading && !tickets.data && <Loading />}
        {tickets.error && <ErrorBox message={tickets.error} onRetry={tickets.reload} />}
        {tickets.data && <DataTable columns={ticketCols} rows={ts} rowKey={t => t.ticketId}
          empty={g.status === 'DRAFT' ? 'No tickets yet. Open enrolment first.' : 'No tickets yet. Use Enrol above.'} />}
      </Card>

      {preActive && (
        <Card title="Activation" actions={canChange && g.status === 'FULL'
          ? <button className="btn btn-primary" disabled={!r?.ready} onClick={() => setDialog({ kind: 'activate' })}>Activate</button> : undefined}>
          {readiness.loading && !r && <Loading what="Checking readiness" />}
          {readiness.error && <ErrorBox message={readiness.error} onRetry={readiness.reload} />}
          {r && (
            <>
              <div className={`banner ${r.ready ? 'banner-good' : 'banner-warn'}`} role="status">
                {r.ready ? `Ready to activate. The first instalment falls due on ${date(r.firstDueDate)}.` : 'Not ready to activate yet.'}
              </div>
              {r.blockers.length > 0 && <ul>{r.blockers.map((b, i) => <li key={i}><strong>{b.code}</strong>: {b.message}</li>)}</ul>}
              {g.status !== 'FULL' && <p className="muted" style={{ marginBottom: 0 }}>A group can be activated once every seat is taken.</p>}
            </>
          )}
        </Card>
      )}

      {cycles.data && cycles.data.length > 0 && (
        <Card title={`Cycles (${cycles.data.length})`}>
          <DataTable columns={cycleCols} rows={cycles.data} rowKey={c => c.cycleNo} />
        </Card>
      )}

      {collecting && <GroupCollectionCard code={g.groupCode} refresh={tick} />}

      {collecting && (
        <Card title="Recent payments" actions={<Link className="btn" to={`/chit/dues?group=${encodeURIComponent(g.groupCode)}&view=OPEN`}>Record a payment from Dues</Link>}>
          {payments.loading && !payments.data && <Loading />}
          {payments.error && <ErrorBox message={payments.error} onRetry={payments.reload} />}
          {payments.data && <PaymentsTable rows={payments.data} showTicket canChange={canChange} onReceipt={setReceipt} onReverse={setReversing} />}
        </Card>
      )}

      {receipt && <ReceiptModal code={g.groupCode} paymentRef={receipt.paymentRef} onClose={() => setReceipt(null)} />}
      {reversing && (
        <ReversePaymentDialog p={reversing} actor={actor}
          onDone={r => { setReversing(null); done(`Payment ${r.paymentRef} reversed.`); }} onClose={() => setReversing(null)} />
      )}

      {dialog?.kind === 'open' && (
        <ConfirmModal title="Open enrolment?" confirmLabel="Open enrolment"
          rows={[['Group', g.groupCode], ['Acting as', actor]]}
          note="Tickets can then be enrolled. Once the first ticket is enrolled the money terms of the rules are locked."
          onConfirm={async () => { const x = await post<ChitGroup>('/open-enrolment'); done(`Enrolment is open${replayed(x)}.`); }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'enrol' && quote.data && (
        <EnrolDialog q={quote.data} code={g.groupCode} actor={actor} seatsLeft={g.seatsLeft}
          onSubmit={async body => {
            const x = await post<ChitTicket>('/tickets', body);
            done(`Ticket ${x.ticketNo} enrolled for customer ${x.customerId}${replayed(x)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'verify' && (
        <ConfirmModal title={`Verify the security of ticket ${dialog.ticket.ticketNo}?`} confirmLabel="Verify"
          rows={[['Customer', dialog.ticket.customerId], ['Security', dialog.ticket.security ? `${label(dialog.ticket.security.kind)} ${money(dialog.ticket.security.amount)}` : '-'], ['Verifying as', actor]]}
          note="The person who recorded the security cannot verify it. Type a different name under Acting as if the service refuses."
          onConfirm={async () => { const x = await post<ChitTicket>(`/tickets/${dialog.ticket.ticketNo}/security/verify`); done(`Security of ticket ${x.ticketNo} verified${replayed(x)}.`); }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'withdraw' && (
        <ConfirmModal title={`Withdraw ticket ${dialog.ticket.ticketNo}?`} confirmLabel="Withdraw" danger
          rows={[['Customer', dialog.ticket.customerId], ['Acting as', actor]]}
          note={<Field label="Reason"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={200} /></Field>}
          onConfirm={async () => {
            const x = await post<ChitWithdrawResult>(`/tickets/${dialog.ticket.ticketNo}/withdraw`, { reason: reason.trim() || 'Withdrawn' });
            done(`Ticket ${dialog.ticket.ticketNo} withdrawn; ${money(x.refund.cashRefunded)} refunded; the group is now ${label(x.groupStatus)}${replayed(x)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'activate' && (
        <ConfirmModal title="Activate this group?" confirmLabel="Activate"
          rows={[['Group', g.groupCode], ['Tickets', live.length], ['First instalment', date(r?.firstDueDate)], ['Acting as', actor]]}
          note="This freezes the rules, activates every ticket and lays out the cycle calendar. It cannot be undone."
          onConfirm={async () => {
            const x = await post<ChitActivation>('/activate');
            done(`Group activated with ${x.cycles.length} cycles${replayed(x)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}

      {dialog?.kind === 'cancel' && (
        <ConfirmModal title="Cancel this group?" confirmLabel="Cancel group" danger typeToConfirm={g.groupCode}
          rows={[['Group', g.groupCode], ['Tickets enrolled', live.length], ['Acting as', actor]]}
          note={<>Everything received is refunded. <Field label="Reason (required)"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={200} /></Field></>}
          onConfirm={async () => {
            if (!reason.trim()) throw new Error('A reason is required');
            const x = await post<ChitCancelResult>('/cancel', { reason: reason.trim() });
            done(`Group cancelled; ${money(x.refund.cashRefunded)} refunded${replayed(x)}.`);
          }}
          onClose={() => setDialog(null)} />
      )}
    </>
  );
}

function QuoteLine({ q }: { q: ChitQuote }) {
  const items: [string, ReactNode][] = [
    ['Enrolment fee', money(q.enrolmentFee)], ['Registration fee', money(q.registrationFee)],
    ['Security deposit (cash)', money(q.securityDeposit)],
  ];
  if (q.lienAmount > 0) items.push(['Lien on deposit (no cash)', money(q.lienAmount)]);
  if (q.guarantorRequired) items.push(['Guarantor', 'Required']);
  items.push(['Cash to collect now', <strong key="due">{money(q.cashDue)}</strong>]);
  return <KeyValues items={items} />;
}

function EnrolDialog({ q, code, actor, seatsLeft, onSubmit, onClose }: {
  q: ChitQuote; code: string; actor: string; seatsLeft: number; onSubmit: (body: Record<string, unknown>) => Promise<void>; onClose: () => void;
}) {
  const [customer, setCustomer] = useState('');
  const [payout, setPayout] = useState('');
  const [guarantor, setGuarantor] = useState('');
  const [lien, setLien] = useState('');

  async function confirm() {
    if (!wholeNumber(customer)) throw new Error('Customer id must be a whole number');
    if (q.guarantorRequired && !wholeNumber(guarantor)) throw new Error('A guarantor customer id is required');
    if (q.lienAmount > 0 && !lien.trim()) throw new Error('The lien reference is required');
    const body: Record<string, unknown> = {
      customerId: Number(customer.trim()),
      enrolmentFeeReceived: q.enrolmentFee, registrationFeeReceived: q.registrationFee, securityDepositReceived: q.securityDeposit,
    };
    if (payout.trim()) body.payoutAccountRef = payout.trim();
    if (q.guarantorRequired) body.guarantorCustomerId = Number(guarantor.trim());
    if (q.lienAmount > 0) body.lienReference = lien.trim();
    await onSubmit(body);
  }

  return (
    <ConfirmModal title={`Enrol a ticket in ${code}`} confirmLabel={q.cashDue > 0 ? `Enrol, ${money(q.cashDue)} received` : 'Enrol'}
      rows={[['Next seat', `${seatsLeft} seat(s) left; the lowest free number is used`], ['Cash to collect', money(q.cashDue)], ['Acting as', actor]]}
      note={(
        <>
          <Field label="Customer id"><input inputMode="numeric" value={customer} onChange={e => setCustomer(e.target.value)} autoFocus /></Field>
          <Field label="Payout account (optional)"><input value={payout} onChange={e => setPayout(e.target.value)} maxLength={60} /></Field>
          {q.guarantorRequired && <Field label="Guarantor customer id"><input inputMode="numeric" value={guarantor} onChange={e => setGuarantor(e.target.value)} /></Field>}
          {q.lienAmount > 0 && <Field label="Lien reference"><input value={lien} onChange={e => setLien(e.target.value)} /></Field>}
          <p className="muted">Confirming records that the amounts in the quote were received. The service refuses the enrolment if they do not match the rules.</p>
        </>)}
      onConfirm={confirm} onClose={onClose} />
  );
}
