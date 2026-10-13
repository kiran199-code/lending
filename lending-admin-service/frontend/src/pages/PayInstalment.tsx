import { FormEvent, useEffect, useState } from 'react';
import { send } from '../api';
import { AccountFacts, AccountFinder, BusinessDateBar, ChannelSelect, ConfirmModal, Field, Notice, payable, useAccount, useBusinessDate, useIdempotencyKey } from '../components/ops';
import { Card, KeyValues, PageHeader, PaymentBadge, StatusBadge } from '../components/ui';
import { date, label, money } from '../format';
import type { PaymentResult } from '../types';

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

  // Defaults: the business date, and exactly the instalment that is due (the service rejects any other amount).
  useEffect(() => {
    if (a) setAmount(a.figures.nextEmiAmount !== null ? String(a.figures.nextEmiAmount) : '');
  }, [a?.accountId, a?.figures.nextEmiAmount]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (bd.data && !valueDate) setValueDate(bd.data.businessDate); }, [bd.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const ok = !!a && payable(a.accountStatus) && a.figures.nextDueDate !== null;
  const fingerprint = `${a?.accountId}|${valueDate}|${amount}|${channel}|${a?.figures.installmentsPaid}`;

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!valueDate) return setError('Choose the value date');
    if (!(Number(amount) > 0)) return setError('Amount must be greater than zero');
    setConfirming(true);
  }

  async function pay() {
    if (!a) return;
    const r = await send<PaymentResult>('POST', `/operations/accounts/${a.accountId}/payments/emi`,
      { valueDate, amount: Number(amount), channel }, undefined, { 'Idempotency-Key': key.keyFor(fingerprint) });
    key.done();
    setResult(r);
    setConfirming(false);
    setNotice({ tone: 'good', text: `Instalment ${r.installmentNumber ?? ''} of ${a.accountNumber} posted.` });
    await finder.refresh();
  }

  return (
    <>
      <PageHeader title="Pay instalment" sub="Post the scheduled EMI that is due on an account" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <AccountFinder finder={finder} hint="The amount must equal the instalment due exactly; for any other amount use Part-payment." />
      {a && <AccountFacts a={a} />}
      {a && !ok && (
        <div className="banner banner-warn" role="status">
          {payable(a.accountStatus) ? 'This account has no instalment left to pay.' : <>This account is <StatusBadge status={a.accountStatus} />, so no instalment can be posted.</>}
        </div>
      )}
      {a && ok && (
        <Card title="Instalment payment">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Value date" hint="Defaults to the business date"><input type="date" value={valueDate} onChange={e => setValueDate(e.target.value)} /></Field>
              <Field label="Amount" hint={`Instalment due: ${money(a.figures.nextEmiAmount)} on ${date(a.figures.nextDueDate)}`}>
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
            ['Transaction', result.transactionId], ['Instalment', result.installmentNumber ?? '-'], ['Value date', date(result.valueDate)],
            ['Amount', money(result.amount)], ['Principal', money(result.principalPaid)], ['Interest', money(result.interestPaid)],
            ['Channel', label(result.paymentChannel)], ['Status', <PaymentBadge status={result.paymentStatus} />],
          ]} />
        </Card>
      )}
      {confirming && a && (
        <ConfirmModal title="Post this instalment?" businessDate={bd.data?.businessDate}
          rows={[['Account', a.accountNumber], ['Value date', date(valueDate)], ['Amount', money(Number(amount))],
            ['Instalment due', `${money(a.figures.nextEmiAmount)} on ${date(a.figures.nextDueDate)}`], ['Channel', label(channel)]]}
          note={valueDate !== bd.data?.businessDate ? 'The value date is not the business date.' : undefined}
          confirmLabel="Post instalment" onConfirm={pay} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
