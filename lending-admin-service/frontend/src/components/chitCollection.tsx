import { useState } from 'react';
import { Link } from 'react-router-dom';
import { get, send } from '../api';
import { Tone, date, dateTime, label, money } from '../format';
import { useLoad } from '../hooks';
import { ChitBadge } from './chit';
import DataTable, { Col } from './DataTable';
import { ConfirmModal, Field, useIdempotencyKey } from './ops';
import { Badge, Card, ErrorBox, KeyValues, Loading } from './ui';
import type { ChitAllocation, ChitGroupStatement, ChitPayment } from '../types';

/** Payment channels the chit service accepts. */
export const CHIT_CHANNELS: [string, string][] = [
  ['UPI', 'UPI'], ['BANK_TRANSFER', 'Bank transfer'], ['CASH_AT_FOREMAN', 'Cash at foreman'], ['AUTO_DEBIT', 'Auto debit'],
];

export const channelName = (c: string) => CHIT_CHANNELS.find(([v]) => v === c)?.[1] ?? label(c);

export function demandTone(status: string): Tone {
  return status === 'PAID' ? 'good' : status === 'OPEN' ? 'warn' : 'neutral';
}
export function paymentTone(status: string): Tone {
  return status === 'RECEIVED' ? 'good' : status === 'REVERSED' ? 'bad' : 'neutral';
}
export function DemandBadge({ status }: { status: string }) {
  return <Badge tone={demandTone(status)}>{label(status)}</Badge>;
}
export function PaymentStateBadge({ status }: { status: string }) {
  return <Badge tone={paymentTone(status)}>{label(status)}</Badge>;
}

/** Days overdue as text; zero or less means not yet late. */
export const overdueText = (d: number) => (d > 0 ? `${d} day${d === 1 ? '' : 's'}` : '-');

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/**
 * Record money received for one ticket. The service applies it (late fees, oldest arrears, current instalment; the rest is held as
 * advance) and refuses an amount larger than the ticket can owe. The same request keeps one Idempotency-Key until it succeeds.
 */
export function RecordPaymentDialog({ code, ticketNo, customerId, suggested, actor, onDone, onClose }: {
  code: string; ticketNo: number; customerId?: number; suggested?: number; actor: string;
  onDone: (p: ChitPayment) => void; onClose: () => void;
}) {
  const [amount, setAmount] = useState(suggested && suggested > 0 ? suggested.toFixed(2) : '');
  const [channel, setChannel] = useState('UPI');
  const [ref, setRef] = useState('');
  const keys = useIdempotencyKey();

  async function confirm() {
    if (!AMOUNT.test(amount.trim()) || Number(amount) <= 0) throw new Error('Enter the amount as a positive number with at most two decimals');
    const body: Record<string, unknown> = { amount: Number(amount.trim()), channel };
    if (ref.trim()) body.externalRef = ref.trim();
    const path = `/chit/groups/${encodeURIComponent(code)}/tickets/${ticketNo}/payments`;
    const p = await send<ChitPayment>('POST', path, body, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|${path}|${JSON.stringify(body)}`) });
    keys.done();
    onDone(p);
  }

  return (
    <ConfirmModal title={`Record a payment: ${code} ticket ${ticketNo}`} confirmLabel="Record payment"
      rows={[['Group', code], ['Ticket', ticketNo], ...(customerId !== undefined ? [['Customer', customerId] as [string, number]] : []), ['Recorded by', actor]]}
      note={(
        <>
          <Field label="Amount received (INR)"><input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} autoFocus /></Field>
          <Field label="How it was paid">
            <select value={channel} onChange={e => setChannel(e.target.value)}>
              {CHIT_CHANNELS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </Field>
          <Field label="Reference (UPI id, UTR, cheque no.; optional)"><input value={ref} onChange={e => setRef(e.target.value)} maxLength={60} /></Field>
          <p className="muted">
            The amount goes first to late fees, then the oldest unpaid instalments, then the current one. Anything beyond that is held as
            advance for the next instalments. The service refuses an amount larger than the ticket can owe.
          </p>
        </>)}
      onConfirm={confirm} onClose={onClose} />
  );
}

/** Reverse a payment (bounced cheque, wrong ticket). Reopens what it paid and mirrors the ledger entries. */
export function ReversePaymentDialog({ p, actor, onDone, onClose }: {
  p: ChitPayment; actor: string; onDone: (r: ChitPayment) => void; onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const keys = useIdempotencyKey();
  async function confirm() {
    if (!reason.trim()) throw new Error('A reason is required');
    const body = { reason: reason.trim() };
    const path = `/chit/groups/${encodeURIComponent(p.groupCode)}/payments/${encodeURIComponent(p.paymentRef)}/reverse`;
    const r = await send<ChitPayment>('POST', path, body, undefined, { 'Idempotency-Key': keys.keyFor(`${actor}|${path}|${reason.trim()}`) });
    keys.done();
    onDone(r);
  }
  return (
    <ConfirmModal title={`Reverse payment ${p.paymentRef}?`} confirmLabel="Reverse payment" danger
      rows={[['Ticket', `${p.groupCode} #${p.ticketNo}`], ['Amount', money(p.amount)], ['Received on', date(p.businessDate)], ['Reversing as', actor]]}
      note={(
        <>
          <Field label="Reason (required)"><input value={reason} onChange={e => setReason(e.target.value)} maxLength={200} autoFocus /></Field>
          <p className="muted">
            The instalments and late fees it paid become payable again. If its advance has already been used for a later instalment the
            service refuses; reverse the later payment first.
          </p>
        </>)}
      onConfirm={confirm} onClose={onClose} />
  );
}

const allocCols: Col<ChitAllocation>[] = [
  { key: 'cyc', header: 'Cycle', render: a => a.cycleNo ?? '-' },
  { key: 'b', header: 'Applied to', render: a => label(a.bucket) + (a.fromAdvance ? ' (from advance)' : '') },
  { key: 'd', header: 'Demand', render: a => a.demandRef ?? '-' },
  { key: 'amt', header: 'Amount', align: 'right', render: a => money(a.amount) },
];

/** The receipt: one payment with every allocation line. Reads the payment fresh so the lines are always there. */
export function ReceiptModal({ code, paymentRef, onClose }: { code: string; paymentRef: string; onClose: () => void }) {
  const { data: p, error, loading, reload } = useLoad(
    () => get<ChitPayment>(`/chit/groups/${encodeURIComponent(code)}/payments/${encodeURIComponent(paymentRef)}`), [code, paymentRef]);
  const lines = p?.allocations ?? [];
  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={`Receipt ${paymentRef}`}>
        <h2>Receipt {paymentRef}</h2>
        {loading && !p && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {p && (
          <>
            <KeyValues items={[
              ['Ticket', `${p.groupCode} #${p.ticketNo} (customer ${p.customerId})`], ['Amount', <strong key="a">{money(p.amount)}</strong>],
              ['Status', <PaymentStateBadge key="s" status={p.status} />], ['Business date', date(p.businessDate)],
              ['Channel', channelName(p.channel)], ['Reference', p.externalRef ?? '-'], ['Applied to dues', money(p.allocated)], ['Held as advance', money(p.advance)],
              ['Recorded', `${dateTime(p.createdAt)} by ${p.createdBy}`],
              ...(p.status === 'REVERSED' ? [['Reversed', `${dateTime(p.reversedAt)} by ${p.reversedBy ?? '-'}: ${p.reversalReason ?? ''}`] as [string, string]] : []),
            ]} />
            <DataTable columns={allocCols} rows={lines} rowKey={a => `${a.demandRef ?? 'adv'}|${a.bucket}|${a.fromAdvance}`} empty="No allocation lines" />
          </>
        )}
        <div className="modal-foot"><button type="button" className="btn" onClick={onClose}>Close</button></div>
      </div>
    </div>
  );
}

/** Payments table shared by the ticket page and the group page. */
export function PaymentsTable({ rows, showTicket, canChange, onReceipt, onReverse, empty }: {
  rows: ChitPayment[]; showTicket?: boolean; canChange: boolean; onReceipt: (p: ChitPayment) => void; onReverse: (p: ChitPayment) => void; empty?: string;
}) {
  const cols: Col<ChitPayment>[] = [
    { key: 'ref', header: 'Receipt', render: p => <span className="mono">{p.paymentRef}</span> },
    { key: 'date', header: 'Date', render: p => date(p.businessDate) },
    ...(showTicket ? [{
      key: 't', header: 'Ticket', render: (p: ChitPayment) => <Link to={`/chit/groups/${encodeURIComponent(p.groupCode)}/tickets/${p.ticketNo}`}>#{p.ticketNo}</Link>,
    } as Col<ChitPayment>] : []),
    { key: 'amt', header: 'Amount', align: 'right', render: p => money(p.amount) },
    { key: 'ch', header: 'Channel', render: p => channelName(p.channel) },
    { key: 'ext', header: 'Reference', render: p => p.externalRef ?? '-' },
    { key: 'adv', header: 'To advance', align: 'right', render: p => (p.advance > 0 ? money(p.advance) : '-') },
    { key: 'st', header: 'Status', render: p => <PaymentStateBadge status={p.status} /> },
    {
      key: 'act', header: '', render: p => (
        <span style={{ whiteSpace: 'nowrap' }}>
          <button className="btn" onClick={() => onReceipt(p)}>Receipt</button>{' '}
          {canChange && p.status === 'RECEIVED' && <button className="btn" onClick={() => onReverse(p)}>Reverse</button>}
        </span>),
    },
  ];
  return <DataTable columns={cols} rows={rows} rowKey={p => p.paymentRef} empty={empty ?? 'No payments yet'} />;
}

/** Cycle-by-cycle collection for a group, with the tickets that have money overdue. */
export function GroupCollectionCard({ code, refresh }: { code: string; refresh: number }) {
  const { data: s, error, loading, reload } = useLoad(
    () => get<ChitGroupStatement>(`/chit/groups/${encodeURIComponent(code)}/statement`), [code, refresh]);
  return (
    <Card title="Collection" actions={<Link className="btn" to={`/chit/dues?group=${encodeURIComponent(code)}&view=OPEN`}>Open dues of this group</Link>}>
      {loading && !s && <Loading what="Reading the collection statement" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {s && (
        <>
          <KeyValues items={[
            ['As of', date(s.asOf)], ['Due so far', money(s.totalDue)], ['Collected', money(s.totalCollected)],
            ['Outstanding', <strong key="o">{money(s.totalOutstanding)}</strong>], ['of which late fees', money(s.lateFeeOutstanding)],
          ]} />
          <DataTable columns={[
            { key: 'c', header: 'Cycle', render: c => c.cycleNo },
            { key: 's', header: 'Status', render: c => <ChitBadge status={c.status} /> },
            { key: 'd', header: 'Due date', render: c => date(c.dueDate) },
            { key: 'n', header: 'Settled', align: 'right', render: c => `${c.settledDemands} of ${c.demands}` },
            { key: 'due', header: 'Due', align: 'right', render: c => money(c.due) },
            { key: 'col', header: 'Collected', align: 'right', render: c => money(c.collected) },
            { key: 'out', header: 'Outstanding', align: 'right', render: c => money(c.outstanding) },
            { key: 'pct', header: '%', align: 'right', render: c => (c.demands > 0 ? `${Number(c.collectedPct).toFixed(1)}%` : '-') },
          ]} rows={s.cycles} rowKey={c => c.cycleNo} empty="No cycle has been opened for collection yet. Run the demand job from Dues & payments." />
          {s.overdueTickets.length > 0 && (
            <>
              <h3 style={{ marginBottom: 6 }}>Tickets with money overdue</h3>
              <DataTable columns={[
                { key: 't', header: 'Ticket', render: t => <Link to={`/chit/groups/${encodeURIComponent(code)}/tickets/${t.ticketNo}`}>#{t.ticketNo}</Link> },
                { key: 'cu', header: 'Customer', render: t => t.customerId },
                { key: 'n', header: 'Overdue instalments', align: 'right', render: t => t.overdueInstalments },
                { key: 'days', header: 'Oldest', render: t => overdueText(t.oldestDaysOverdue) },
                { key: 'sub', header: 'Subscription', align: 'right', render: t => money(t.subscriptionOutstanding) },
                { key: 'lf', header: 'Late fee', align: 'right', render: t => money(t.lateFeeOutstanding) },
              ]} rows={s.overdueTickets} rowKey={t => t.ticketNo} />
            </>
          )}
        </>
      )}
    </Card>
  );
}
