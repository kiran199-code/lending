import { FormEvent, useEffect, useState } from 'react';
import { send } from '../api';
import { AccountFacts, AccountFinder, BusinessDateBar, ChannelSelect, ConfirmModal, Field, Notice, payable, useAccount, useBusinessDate, useIdempotencyKey } from '../components/ops';
import { Card, KeyValues, PageHeader, PaymentBadge, StatusBadge } from '../components/ui';
import { date, label, money } from '../format';
import type { PaymentResult } from '../types';

export default function Foreclosure() {
  const bd = useBusinessDate();
  const finder = useAccount();
  const a = finder.account;
  const key = useIdempotencyKey();
  const [valueDate, setValueDate] = useState('');
  const [channel, setChannel] = useState('MANUAL');
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<PaymentResult | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  useEffect(() => { if (bd.data && !valueDate) setValueDate(bd.data.businessDate); }, [bd.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const ok = !!a && payable(a.accountStatus);
  const fingerprint = `${a?.accountId}|${valueDate}|${channel}`;

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!valueDate) return setError('Choose the foreclosure date');
    setConfirming(true);
  }

  async function foreclose() {
    if (!a) return;
    const r = await send<PaymentResult>('POST', `/operations/accounts/${a.accountId}/payments/foreclosure`,
      { foreclosureDate: valueDate, channel }, undefined, { 'Idempotency-Key': key.keyFor(fingerprint) });
    key.done();
    setResult(r);
    setConfirming(false);
    setNotice({ tone: 'good', text: `Account ${a.accountNumber} foreclosed and closed.` });
    await finder.refresh();
  }

  return (
    <>
      <PageHeader title="Foreclosure" sub="Pay off the outstanding principal and accrued interest and close the account" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <AccountFinder finder={finder} hint="The payoff amount is worked out by lending-service for the date you choose." />
      {a && <AccountFacts a={a} />}
      {a && !ok && <div className="banner banner-warn" role="status">This account is <StatusBadge status={a.accountStatus} />, so it cannot be foreclosed.</div>}
      {a && ok && (
        <Card title="Foreclosure">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Foreclosure date" hint="Defaults to the business date"><input type="date" value={valueDate} onChange={e => setValueDate(e.target.value)} /></Field>
              <Field label="Channel"><ChannelSelect value={channel} onChange={setChannel} /></Field>
            </div>
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-danger">Review foreclosure</button>
          </form>
        </Card>
      )}
      {result && (
        <Card title="Foreclosed">
          <KeyValues items={[
            ['Transaction', result.transactionId], ['Date', date(result.valueDate)], ['Total paid', money(result.amount)],
            ['Principal', money(result.principalPaid)], ['Interest', money(result.interestPaid)], ['Penal interest', money(result.penalInterestPaid ?? 0)], ['Channel', label(result.paymentChannel)],
            ['Status', <PaymentBadge status={result.paymentStatus} />],
          ]} />
        </Card>
      )}
      {confirming && a && (
        <ConfirmModal title="Foreclose this account?" businessDate={bd.data?.businessDate} danger typeToConfirm="FORECLOSE"
          rows={[['Account', a.accountNumber], ['Foreclosure date', date(valueDate)], ['Outstanding principal', money(a.figures.outstandingPrincipal)], ['Channel', label(channel)]]}
          note="The account is paid off and closed. This cannot be undone."
          confirmLabel="Foreclose account" onConfirm={foreclose} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
