import { FormEvent, ReactNode, useState } from 'react';
import { Link } from 'react-router-dom';
import { get, send } from '../api';
import { BusinessDateBar, ConfirmModal, Field, Notice, useBusinessDate } from '../components/ops';
import { Card, ErrorBox, KeyValues, Loading, PageHeader, StatusBadge } from '../components/ui';
import { label, money, money0, pct } from '../format';
import { useLoad } from '../hooks';
import type { BranchInfo, CurrencyInfo, LoanAccountResult, Product, ProductsResponse } from '../types';

const band = (min: number | null, max: number | null, fmt: (v: number) => string = String) =>
  min === null && max === null ? 'no limit' : `${min === null ? '-' : fmt(min)} to ${max === null ? '-' : fmt(max)}`;
const freqName = (v: string) => label(v).replace('_', '-');

interface Form {
  customerId: string; sanctionRefId: string; productCode: string; amount: string; tenure: string;
  rate: string; spread: string; frequency: string; moratorium: string; balloon: string; stepUp: string;
  currencyCode: string; branchCode: string;
}
const EMPTY: Form = { customerId: '', sanctionRefId: '', productCode: '', amount: '', tenure: '', rate: '', spread: '', frequency: '', moratorium: '', balloon: '', stepUp: '', currencyCode: '', branchCode: '' };

export default function CreateLoan() {
  const bd = useBusinessDate();
  const products = useLoad(() => get<ProductsResponse>('/products'), []);
  const currencies = useLoad(() => get<CurrencyInfo[]>('/accounting/currencies'), []);
  const branches = useLoad(() => get<BranchInfo[]>('/accounting/branches'), []);
  const [f, setF] = useState<Form>(EMPTY);
  const [more, setMore] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<LoanAccountResult | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  const active = (products.data?.products ?? []).filter(p => p.status === 'ACTIVE');
  const product: Product | undefined = active.find(p => p.productCode === f.productCode);
  const set = (k: keyof Form) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  function body() {
    const b: Record<string, unknown> = {
      customerId: Number(f.customerId), sanctionRefId: Number(f.sanctionRefId), productCode: f.productCode,
      sanctionedAmount: Number(f.amount), tenureMonths: Number(f.tenure),
    };
    if (f.currencyCode !== '') b.currencyCode = f.currencyCode;
    if (f.branchCode !== '') b.branchCode = f.branchCode;
    if (f.rate !== '') b.interestRate = Number(f.rate);
    if (f.spread !== '') b.spread = Number(f.spread);
    if (f.frequency !== '') b.repaymentFrequency = f.frequency;
    if (f.moratorium !== '') b.moratoriumMonths = Number(f.moratorium);
    if (f.balloon !== '') b.balloonPercent = Number(f.balloon);
    if (f.stepUp !== '') b.stepUpPercent = Number(f.stepUp);
    return b;
  }

  function check(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!/^\d+$/.test(f.customerId)) return setError('Customer id must be a whole number');
    if (!/^\d+$/.test(f.sanctionRefId)) return setError('Sanction reference must be a whole number; use Generate for a new one');
    if (!f.productCode) return setError('Choose a product');
    if (!(Number(f.amount) > 0)) return setError('Sanctioned amount must be greater than zero');
    if (!/^[1-9]\d*$/.test(f.tenure)) return setError('Tenure must be a whole number of months');
    setConfirming(true);
  }

  async function create() {
    const r = await send<LoanAccountResult>('POST', '/operations/accounts', body());
    setCreated(r);
    setConfirming(false);
    setNotice({ tone: 'good', text: `Account ${r.accountNumber} created. It is waiting to be disbursed.` });
    setF(EMPTY);
    setMore(false);
  }

  const rows: [string, ReactNode][] = [
    ['Customer', f.customerId], ['Sanction reference', f.sanctionRefId], ['Product', product ? `${product.productCode} - ${product.productName}` : f.productCode],
    ['Sanctioned amount', money(Number(f.amount))], ['Tenure', `${f.tenure} months`],
  ];
  if (f.currencyCode !== '') rows.push(['Currency', f.currencyCode]);
  if (f.branchCode !== '') rows.push(['Branch', f.branchCode]);
  if (f.rate !== '') rows.push(['Interest rate', pct(Number(f.rate))]);
  if (f.spread !== '') rows.push(['Spread', pct(Number(f.spread))]);
  if (f.frequency !== '') rows.push(['Repayment frequency', freqName(f.frequency)]);
  if (f.moratorium !== '') rows.push(['Moratorium', `${f.moratorium} months`]);
  if (f.balloon !== '') rows.push(['Balloon', `${f.balloon}%`]);
  if (f.stepUp !== '') rows.push(['Step-up', `${f.stepUp}% a year`]);

  return (
    <>
      <PageHeader title="Create loan" sub="Open a loan account from a product; it stays pending until you disburse it" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {created && (
        <Card title="Account created" actions={<Link className="btn btn-primary" to={`/ops/disburse?account=${encodeURIComponent(created.accountNumber)}`}>Disburse this account</Link>}>
          <KeyValues items={[
            ['Account number', <strong className="mono">{created.accountNumber}</strong>], ['Account id', created.accountId],
            ['Status', <StatusBadge status={created.accountStatus} />], ['Product', created.productCode],
            ['Sanctioned', money(created.sanctionedAmount)], ['Rate', pct(created.interestRate)],
            ['Currency / branch', `${created.currencyCode ?? '-'} / ${created.branchCode ?? '-'}`],
            ['Tenure', `${created.tenureMonths} months`], ['Repayment', `${label(created.scheduleType)}, ${freqName(created.repaymentFrequency)}`],
          ]} />
        </Card>
      )}
      {products.loading && <Loading what="Loading products" />}
      {products.error && <ErrorBox message={products.error} onRetry={products.reload} />}
      {products.data && (
        <Card title="New loan account">
          <form onSubmit={check}>
            <div className="form-grid">
              <Field label="Customer id"><input inputMode="numeric" value={f.customerId} onChange={set('customerId')} /></Field>
              <Field label="Sanction reference" hint="Must be new: a repeat of an existing reference returns that account">
                <div className="inline-form">
                  <input inputMode="numeric" value={f.sanctionRefId} onChange={set('sanctionRefId')} style={{ width: '100%' }} />
                  <button type="button" className="btn" onClick={() => setF({ ...f, sanctionRefId: String(Date.now()) })}>Generate</button>
                </div>
              </Field>
              <Field label="Product">
                <select value={f.productCode} onChange={set('productCode')}>
                  <option value="">Choose a product</option>
                  {active.map(p => <option key={p.productCode} value={p.productCode}>{p.productCode} - {p.productName}</option>)}
                </select>
              </Field>
              <Field label="Sanctioned amount" hint={product ? `Allowed ${band(product.minSanctionedAmount, product.maxSanctionedAmount, money0)}` : undefined}>
                <input type="number" step="0.01" min="0" value={f.amount} onChange={set('amount')} />
              </Field>
              <Field label="Currency" hint="Fixed for the life of the account; the books post in this currency">
                <select value={f.currencyCode} onChange={set('currencyCode')}>
                  <option value="">Default</option>
                  {(currencies.data ?? []).filter(c => c.active).map(c => <option key={c.currencyCode} value={c.currencyCode}>{c.currencyCode} - {c.currencyName}</option>)}
                </select>
              </Field>
              <Field label="Branch" hint="Fixed for the life of the account; journals are booked at this branch">
                <select value={f.branchCode} onChange={set('branchCode')}>
                  <option value="">Default</option>
                  {(branches.data ?? []).filter(b => b.active).map(b => <option key={b.branchCode} value={b.branchCode}>{b.branchCode} - {b.branchName}</option>)}
                </select>
              </Field>
              <Field label="Tenure (months)" hint={product ? `Allowed ${band(product.minTenureMonths, product.maxTenureMonths)}` : undefined}>
                <input inputMode="numeric" value={f.tenure} onChange={set('tenure')} />
              </Field>
            </div>
            {product && (
              <p className="muted">
                Takes from the product unless you override: {label(product.rateType)} rate {product.defaultInterestRate}%
                {product.rateType === 'FLOATING' && ` (${product.benchmarkRateRef} + ${product.defaultSpread ?? 0}%)`}, {label(product.scheduleType)} schedule,
                {' '}{freqName(product.repaymentFrequency).toLowerCase()} repayment, {product.dayCountConvention}.
              </p>
            )}
            <p><button type="button" className="btn" onClick={() => setMore(!more)} aria-expanded={more}>{more ? 'Hide' : 'Override'} product terms</button></p>
            {more && product && (
              <div className="form-grid">
                <Field label="Interest rate %" hint={`Band ${band(product.minInterestRate, product.maxInterestRate, v => `${v}%`)}`}>
                  <input type="number" step="0.01" value={f.rate} onChange={set('rate')} placeholder={String(product.defaultInterestRate)} />
                </Field>
                {product.rateType === 'FLOATING' && (
                  <Field label="Spread %" hint={`Band ${band(product.minSpread, product.maxSpread, v => `${v}%`)}`}>
                    <input type="number" step="0.01" value={f.spread} onChange={set('spread')} placeholder={String(product.defaultSpread ?? '')} />
                  </Field>
                )}
                <Field label="Repayment frequency">
                  <select value={f.frequency} onChange={set('frequency')}>
                    <option value="">Product default ({freqName(product.repaymentFrequency)})</option>
                    {product.allowedRepaymentFrequencies.map(v => <option key={v} value={v}>{freqName(v)}</option>)}
                  </select>
                </Field>
                <Field label="Moratorium (months)" hint={`Up to ${product.maxMoratoriumMonths ?? 'no limit'}`}>
                  <input inputMode="numeric" value={f.moratorium} onChange={set('moratorium')} placeholder={String(product.moratoriumMonths)} />
                </Field>
                {product.scheduleType === 'BALLOON' && (
                  <Field label="Balloon %" hint={`Up to ${product.maxBalloonPercent ?? 'no limit'}`}>
                    <input type="number" step="0.01" value={f.balloon} onChange={set('balloon')} placeholder={String(product.balloonPercent)} />
                  </Field>
                )}
                {product.scheduleType === 'STEP_UP_EMI' && (
                  <Field label="Step-up % a year" hint={`Up to ${product.maxStepUpPercent ?? 'no limit'}`}>
                    <input type="number" step="0.01" value={f.stepUp} onChange={set('stepUp')} placeholder={String(product.stepUpPercent)} />
                  </Field>
                )}
              </div>
            )}
            {more && !product && <p className="muted">Choose a product first.</p>}
            {error && <div className="banner banner-bad" role="alert">{error}</div>}
            <button className="btn btn-primary">Review and create</button>
          </form>
        </Card>
      )}
      {confirming && (
        <ConfirmModal title="Create this loan account?" businessDate={bd.data?.businessDate} rows={rows}
          note="The account is created pending disbursement. No money moves until you disburse it."
          confirmLabel="Create account" onConfirm={create} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
