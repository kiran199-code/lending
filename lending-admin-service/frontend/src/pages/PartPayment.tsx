import { FormEvent, useEffect, useState } from 'react';
import { send } from '../api';
import { AccountFacts, AccountFinder, BusinessDateBar, ChannelSelect, ConfirmModal, Field, Notice, payable, useAccount, useBusinessDate, useIdempotencyKey } from '../components/ops';
import { Card, KeyValues, PageHeader, PaymentBadge, StatusBadge } from '../components/ui';
import { date, label, money } from '../format';
import type { PaymentResult } from '../types';

export default function PartPayment() {
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

  useEffect(() => { if (bd.data && !valueDate) setValueDate(bd.data.businessDate); }, [bd.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const ok = !!a && payable(a.accountStatus);
  const outstanding = a?.figures.outstandingPrincipal ?? null;
  const fingerprint = `${a?.accountId}|${valueDate}|${amount}|${channel}|${a?.figures.installmentsPaid}|${outstanding}`;

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!valueDate) return setError('Choose the value date');
    if (!(Number(amount) > 0)) return setError('Amount must be greater than zero');
    if (outstanding !== null && Number(amount) >= outstanding) return setError(`A part-payment must be less than the outstanding principal (${money(outstanding)}); use Foreclosure to pay the loan off`);
    setConfirming(true);
  }

  async function pay() {
    if (!a) return;
    const r = await send<PaymentResult>('POST', `/operations/accounts/${a.accountId}/payments/part-payment`,
      { valueDate, amount: Number(amount), channel }, undefined, { 'Idempotency-Key': key.keyFor(fingerprint) });
    key.done();
    setResult(r);
    setConfirming(false);
    setAmount('');
    setNotice({ tone: 'good', text: `Part-payment on ${a.accountNumber} posted. The remaining schedule has been regenerated.` });
    await finder.refresh();
  }

  return (
    <>
      <PageHeader title="Part-payment" sub="Pay down principal ahead of schedule; the remaining schedule is regenerated" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <AccountFinder finder={finder} hint="Any amount below the outstanding principal. To close the loan use Foreclosure." />
      {a && <AccountFacts a={a} />}
      {a && !ok && <div className="banner banner-warn" role="status">This account is <StatusBadge status={a.accountStatus} />, so no part-payment can be posted.</div>}
      {a && ok && (
        <Card title="Part-payment">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Value date" hint="Defaults to the business date"><input type="date" value={valueDate} onChange={e => setValueDate(e.target.value)} /></Field>
              <Field label="Amount" hint={`Outstanding principal ${money(outstanding)}`}>
                <input type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} />
              </Field>
              <Field label="Channel"><ChannelSelect value={channel} onChange={setChannel} /></Field>
            </div>
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary">Review and pay</button>
          </form>
        </Card>
      )}
      {result && (
        <Card title="Posted">
          <KeyValues items={[
            ['Transaction', result.transactionId], ['Value date', date(result.valueDate)], ['Amount', money(result.amount)],
            ['Principal', money(result.principalPaid)], ['Interest', money(result.interestPaid)], ['Penal interest', money(result.penalInterestPaid ?? 0)], ['Channel', label(result.paymentChannel)],
            ['Status', <PaymentBadge status={result.paymentStatus} />],
          ]} />
        </Card>
      )}
      {confirming && a && (
        <ConfirmModal title="Post this part-payment?" businessDate={bd.data?.businessDate}
          rows={[['Account', a.accountNumber], ['Value date', date(valueDate)], ['Amount', money(Number(amount))],
            ['Outstanding principal now', money(outstanding)], ['Channel', label(channel)]]}
          note="The amount is applied to principal and the remaining instalments are recalculated."
          confirmLabel="Post part-payment" onConfirm={pay} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
