import { FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { send } from '../api';
import { CollateralCard } from '../components/limits';
import { AccountFacts, AccountFinder, BusinessDateBar, ConfirmModal, Field, Notice, addMonths, useAccount, useBusinessDate } from '../components/ops';
import { Card, KeyValues, PageHeader, StatusBadge } from '../components/ui';
import { date, label, money, pct } from '../format';
import type { LoanAccountResult } from '../types';

export default function DisburseLoan() {
  const bd = useBusinessDate();
  const finder = useAccount();
  const a = finder.account;
  const [amount, setAmount] = useState('');
  const [firstDue, setFirstDue] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<LoanAccountResult | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  // Defaults: the whole sanctioned amount, and a first instalment one month after the business date.
  useEffect(() => {
    if (a && a.accountStatus === 'PENDING_DISBURSEMENT') {
      setAmount(String(a.sanctionedAmount));
      if (bd.data) setFirstDue(addMonths(bd.data.businessDate, 1));
    }
  }, [a?.accountId, a?.accountStatus, bd.data?.businessDate]); // eslint-disable-line react-hooks/exhaustive-deps

  const pending = a?.accountStatus === 'PENDING_DISBURSEMENT';
  const partial = a && Number(amount) > 0 && Number(amount) < a.sanctionedAmount;

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!(Number(amount) > 0)) return setError('Disbursed amount must be greater than zero');
    if (a && Number(amount) > a.sanctionedAmount) return setError(`Disbursed amount cannot exceed the sanctioned amount (${money(a.sanctionedAmount)})`);
    if (!firstDue) return setError('Choose the first instalment due date');
    if (bd.data && firstDue <= bd.data.businessDate) return setError('The first instalment must fall after the business date');
    setConfirming(true);
  }

  async function disburse() {
    if (!a) return;
    const r = await send<LoanAccountResult>('POST', `/operations/accounts/${a.accountId}/disburse`, { disbursedAmount: Number(amount), firstEmiDueDate: firstDue });
    setResult(r);
    setConfirming(false);
    setNotice({ tone: 'good', text: `Account ${r.accountNumber} disbursed. Its repayment schedule has been generated.` });
    await finder.refresh();
  }

  return (
    <>
      <PageHeader title="Disburse loan" sub="Release the sanctioned amount, in full or in part, and generate the repayment schedule" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <AccountFinder finder={finder} hint="Only accounts that are pending disbursement can be disbursed." />
      {a && <AccountFacts a={a} />}
      {a && !pending && !result && <div className="banner banner-warn" role="status">This account is <StatusBadge status={a.accountStatus} />, so it cannot be disbursed again.</div>}
      {a && pending && <CollateralCard account={a} />}
      {a && pending && (
        <Card title="Disbursement">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Amount to disburse" hint={`Sanctioned ${money(a.sanctionedAmount)}. A smaller amount is a part-disbursement.`}>
                <input type="number" step="0.01" min="0" value={amount} onChange={e => setAmount(e.target.value)} />
              </Field>
              <Field label="First instalment due date" hint="After the business date; later instalments follow the repayment frequency">
                <input type="date" value={firstDue} onChange={e => setFirstDue(e.target.value)} />
              </Field>
            </div>
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary">Review and disburse</button>
          </form>
        </Card>
      )}
      {result && (
        <Card title="Disbursed" actions={<Link className="btn" to={`/accounts/${result.accountId}`}>Open full inquiry</Link>}>
          <KeyValues items={[
            ['Account', <strong className="mono">{result.accountNumber}</strong>], ['Status', <StatusBadge status={result.accountStatus} />],
            ['Disbursed', money(result.disbursedAmount)], ['EMI', money(result.emiAmount)], ['First instalment', date(result.emiStartDate)],
            ['Rate', pct(result.interestRate)], ['Tenure', `${result.tenureMonths} months`], ['Schedule', label(result.scheduleType)],
          ]} />
        </Card>
      )}
      {confirming && a && (
        <ConfirmModal title="Disburse this account?" businessDate={bd.data?.businessDate}
          rows={[['Account', a.accountNumber], ['Product', a.productCode], ['Sanctioned', money(a.sanctionedAmount)],
            ['Amount to disburse', money(Number(amount))], ['Type', partial ? 'Part-disbursement' : 'Full disbursement'], ['First instalment due', date(firstDue)]]}
          note="The schedule is generated and the account becomes active. This cannot be undone from the console."
          confirmLabel="Disburse" onConfirm={disburse} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
