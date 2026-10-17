import { FormEvent, useEffect, useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { AccountFacts, AccountFinder, BusinessDateBar, ChannelSelect, ConfirmModal, Field, Notice, payable, useAccount, useBusinessDate, useIdempotencyKey } from '../components/ops';
import { Card, KeyValues, Kpi, PageHeader, PaymentBadge, StatusBadge } from '../components/ui';
import { date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { AllocationLine, AmountPayable, DemandLine, PaymentPreview, PaymentResult } from '../types';

const COMPONENT: Record<string, string> = { PENALTY: 'Penal interest', INTEREST: 'Interest', PRINCIPAL: 'Principal', ADVANCE: 'Held as advance' };

const demandColumns: Col<DemandLine>[] = [
  { key: 'n', header: 'Instalment', render: r => r.installmentNumber },
  { key: 'd', header: 'Due date', render: r => date(r.dueDate) },
  { key: 'i', header: 'Interest owed', align: 'right', render: r => money(r.interestOutstanding) },
  { key: 'p', header: 'Principal owed', align: 'right', render: r => money(r.principalOutstanding) },
  { key: 'pn', header: 'Penal interest', align: 'right', render: r => money(r.penalOutstanding) },
  { key: 't', header: 'Total owed', align: 'right', render: r => money(r.outstanding) },
  { key: 's', header: 'Status', render: r => (r.raised ? label(r.status) : 'Due, demand not raised yet') },
];

const allocationColumns: Col<AllocationLine>[] = [
  { key: 'n', header: 'Instalment', render: r => r.installmentNumber ?? '-' },
  { key: 'c', header: 'Applied to', render: r => COMPONENT[r.component] ?? label(r.component) },
  { key: 'a', header: 'Amount', align: 'right', render: r => money(r.amount) },
];

/** Waits a moment after the user stops typing before returning the value, so the preview is not called on every keystroke. */
function useDebounced<T>(value: T, ms = 400): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export default function PayInstalment() {
  const bd = useBusinessDate();
  const finder = useAccount();
  const a = finder.account;
  const key = useIdempotencyKey();
  const [valueDate, setValueDate] = useState('');
  const [amount, setAmount] = useState('');
  const [channel, setChannel] = useState('MANUAL');
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PaymentResult | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [preview, setPreview] = useState<PaymentPreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);

  useEffect(() => { if (bd.data && !valueDate) setValueDate(bd.data.businessDate); }, [bd.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const ok = !!a && payable(a.accountStatus);
  const accountId = a?.accountId;

  // What is owed on the value date. A fresh read after every posted payment (installmentsPaid / totalPaid change).
  const owed = useLoad<AmountPayable | null>(
    () => (ok && accountId && valueDate
      ? get<AmountPayable>(`/operations/accounts/${accountId}/amount-payable`, { asOf: valueDate })
      : Promise.resolve(null)),
    [ok, accountId, valueDate, a?.figures.installmentsPaid, a?.figures.totalPaid]);
  const payableNow = owed.data;

  // Default the amount to everything payable on the date, or to the next instalment when nothing is due yet (an advance payment).
  useEffect(() => {
    if (!payableNow) return;
    const suggested = payableNow.amountPayable > 0 ? payableNow.amountPayable : payableNow.nextInstallment?.amount ?? 0;
    setAmount(suggested > 0 ? suggested.toFixed(2) : '');
  }, [payableNow?.accountId, payableNow?.asOfDate, payableNow?.amountPayable]); // eslint-disable-line react-hooks/exhaustive-deps

  // Show how the amount would be applied, without posting anything.
  const wantedAmount = useDebounced(amount);
  useEffect(() => {
    let live = true;
    setPreview(null);
    setPreviewError(null);
    if (!ok || !accountId || !valueDate || !(Number(wantedAmount) > 0)) return;
    send<PaymentPreview>('POST', `/operations/accounts/${accountId}/payments/preview`,
      { valueDate, amount: Number(wantedAmount), paymentType: 'EMI' })
      .then(p => { if (live) setPreview(p); })
      .catch(e => { if (live) setPreviewError(e instanceof Error ? e.message : String(e)); });
    return () => { live = false; };
  }, [ok, accountId, valueDate, wantedAmount]);

  const fingerprint = `${a?.accountId}|${valueDate}|${amount}|${channel}|${a?.figures.installmentsPaid}|${a?.figures.totalPaid}`;
  const blocked = preview !== null && !preview.allowed;

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!valueDate) return setError('Choose the value date');
    if (!(Number(amount) > 0)) return setError('Amount must be greater than zero');
    if (blocked) return setError(preview?.message ?? 'This payment cannot be posted');
    setConfirming(true);
  }

  async function pay() {
    if (!a) return;
    const r = await send<PaymentResult>('POST', `/operations/accounts/${a.accountId}/payments/emi`,
      { valueDate, amount: Number(amount), channel }, undefined, { 'Idempotency-Key': key.keyFor(fingerprint) });
    key.done();
    setResult(r);
    setConfirming(false);
    setNotice({ tone: 'good', text: `Payment of ${money(r.amount)} on ${a.accountNumber} posted.` });
    await finder.refresh();
    owed.reload();
  }

  return (
    <>
      <PageHeader title="Pay instalment" sub="Pay what is owed on an account: penal interest first, then the oldest instalment, interest before principal" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <AccountFinder finder={finder} hint="Any amount can be paid. It clears the oldest demand first (interest, then principal); anything left over is held as an advance for the next instalment." />
      {a && <AccountFacts a={a} />}
      {a && !ok && (
        <div className="banner banner-warn" role="status">
          This account is <StatusBadge status={a.accountStatus} />, so no payment can be posted.
        </div>
      )}
      {a && ok && owed.error && <div className="banner banner-bad" role="alert">{owed.error}</div>}
      {a && ok && payableNow && (
        <Card title={`Amount payable on ${date(payableNow.asOfDate)}`}>
          <div className="kpis">
            <Kpi title="Overdue" value={money(payableNow.overdueAmount)} tone={payableNow.overdueAmount > 0 ? 'bad' : 'good'}
              sub={payableNow.daysPastDue > 0 ? `${payableNow.daysPastDue} days past due` : 'Nothing overdue'} />
            <Kpi title="Due today" value={money(payableNow.dueTodayAmount)} />
            <Kpi title="Penal interest" value={money(payableNow.penalInterest)} tone={payableNow.penalInterest > 0 ? 'warn' : undefined}
              sub="Included in the total" />
            <Kpi title="Advance held" value={money(payableNow.advanceBalance)} />
            <Kpi title="Total payable" value={money(payableNow.amountPayable)} tone={payableNow.amountPayable > 0 ? 'warn' : 'good'}
              sub={payableNow.amountPayable === 0 && payableNow.nextInstallment
                ? `Next: ${money(payableNow.nextInstallment.amount)} on ${date(payableNow.nextInstallment.dueDate)}`
                : undefined} />
          </div>
          {payableNow.demands.length > 0 && (
            <DataTable columns={demandColumns} rows={payableNow.demands} rowKey={r => r.installmentNumber}
              rowClass={r => (r.dueDate < payableNow.asOfDate ? 'row-bad' : undefined)} />
          )}
        </Card>
      )}
      {a && ok && (
        <Card title="Payment">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Value date" hint="Defaults to the business date"><input type="date" value={valueDate} onChange={e => setValueDate(e.target.value)} /></Field>
              <Field label="Amount" hint={payableNow ? `Total payable: ${money(payableNow.amountPayable)}` : undefined}>
                <input type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} />
              </Field>
              <Field label="Channel"><ChannelSelect value={channel} onChange={setChannel} /></Field>
            </div>
            {previewError && <div className="banner banner-warn" role="status">{previewError}</div>}
            {preview && !preview.allowed && <div className="banner banner-bad" role="alert">{preview.message ?? 'This payment cannot be posted'}</div>}
            {preview && preview.allowed && (
              <>
                <p style={{ marginBottom: 4 }}><strong>This payment will be applied as:</strong></p>
                <DataTable columns={allocationColumns} rows={preview.lines} rowKey={r => `${r.installmentNumber}-${r.component}-${r.amount}`} />
                {preview.surplus > 0 && <p className="hint">{money(preview.surplus)} is more than everything due and is held as an advance against the next instalment.</p>}
              </>
            )}
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary" disabled={blocked}>Review and pay</button>
          </form>
        </Card>
      )}
      {result && (
        <Card title="Posted">
          <KeyValues items={[
            ['Transaction', result.transactionId], ['Value date', date(result.valueDate)],
            ['Amount', money(result.amount)], ['Penal interest', money(result.penalInterestPaid ?? 0)], ['Interest', money(result.interestPaid)], ['Principal', money(result.principalPaid)],
            ['Advance held now', money(result.advanceBalance ?? 0)],
            ['Channel', label(result.paymentChannel)], ['Status', <PaymentBadge status={result.paymentStatus} />],
          ]} />
          {result.allocations && result.allocations.length > 0 && (
            <DataTable columns={allocationColumns} rows={result.allocations} rowKey={r => `${r.installmentNumber}-${r.component}-${r.amount}`} />
          )}
        </Card>
      )}
      {confirming && a && (
        <ConfirmModal title="Post this payment?" businessDate={bd.data?.businessDate}
          rows={[['Account', a.accountNumber], ['Value date', date(valueDate)], ['Amount', money(Number(amount))],
            ['Total payable', money(payableNow?.amountPayable)], ['Channel', label(channel)]]}
          note={valueDate !== bd.data?.businessDate ? 'The value date is not the business date.' : undefined}
          confirmLabel="Post payment" onConfirm={pay} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
