import { FormEvent, useEffect, useRef, useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { count, label, money0 } from '../format';
import { useLoad } from '../hooks';
import type { Product, ProductInput, ProductsResponse } from '../types';

const range = (min: number | null, max: number | null, fmt: (v: number) => string = String) =>
  min === null && max === null ? 'no limit' : `${min === null ? '-' : fmt(min)} to ${max === null ? '-' : fmt(max)}`;
const pctRange = (min: number | null, max: number | null) => range(min, max, v => `${v}%`);

type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

export default function Products() {
  const { data, error, loading, reload } = useLoad(() => get<ProductsResponse>('/products'), []);
  const [editing, setEditing] = useState<Product | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const editable = !!data?.editable;

  const cols: Col<Product>[] = [
    { key: 'c', header: 'Product', render: r => (<><strong className="mono">{r.productCode}</strong><div className="muted">{r.productName}</div></>) },
    { key: 't', header: 'Type', render: r => (<>{label(r.accountType)} | {label(r.rateType)}<div className="muted">{r.scheduleType} | {r.dayCountConvention}</div></>) },
    { key: 'r', header: 'Default rate', render: r => (<>{r.defaultInterestRate}%{r.rateType === 'FLOATING' && <div className="muted">{r.benchmarkRateRef} + {r.defaultSpread ?? 0}%</div>}</>) },
    { key: 'rb', header: 'Rate / spread band', render: r => (<>{pctRange(r.minInterestRate, r.maxInterestRate)}{r.rateType === 'FLOATING' && <div className="muted">spread {pctRange(r.minSpread, r.maxSpread)}</div>}</>) },
    { key: 'ab', header: 'Amount band', render: r => range(r.minSanctionedAmount, r.maxSanctionedAmount, money0) },
    { key: 'tb', header: 'Tenure (months)', render: r => range(r.minTenureMonths, r.maxTenureMonths) },
    { key: 'st', header: 'Status', render: r => <Badge tone={r.status === 'ACTIVE' ? 'good' : 'neutral'}>{label(r.status)}</Badge> },
    { key: 'n', header: 'Accounts', align: 'right', render: r => count(r.accountCount) },
    {
      key: 'ed', header: '', align: 'right',
      render: r => <button className="btn" disabled={!editable} onClick={() => setEditing(r)} aria-label={`Edit ${r.productCode}`}>Edit</button>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Loan products"
        sub="Default terms and limits for new accounts"
        actions={<button className="btn btn-primary" disabled={!editable} onClick={() => setEditing('new')}>New product</button>}
      />
      <div className="banner">
        A change applies to accounts opened afterwards, and to an individual account when it is rescheduled by a floating rate
        reset (the rate band caps and floors the new rate). Existing accounts keep the terms they were opened with, and nothing
        reschedules all accounts of a product.
      </div>
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {data?.note && <div className="banner banner-warn">{data.note}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <Card title={`Products (${data.products.length})`}>
          <DataTable columns={cols} rows={data.products} rowKey={r => r.productId} empty="No products defined" />
        </Card>
      )}
      {editing && (
        <ProductDialog
          product={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={text => { setEditing(null); setNotice({ tone: 'good', text }); reload(); }}
        />
      )}
    </>
  );
}

// ------------------------------------------------------------------ edit dialog

const DAY_COUNTS: [string, string][] = [['ACT_365', 'ACT/365'], ['ACT_ACT', 'ACT/ACT'], ['THIRTY_360', '30/360']];
const dayEnum = (db: string) => DAY_COUNTS.find(([, l]) => l === db)?.[0] ?? 'ACT_365';

const txt = (v: number | string | null | undefined) => (v === null || v === undefined ? '' : String(v));
const num = (s: string): number | null => (s.trim() === '' ? null : Number(s));

function ProductDialog({ product, onClose, onSaved }: { product: Product | null; onClose: () => void; onSaved: (text: string) => void }) {
  const isNew = product === null;
  const [f, setF] = useState({
    productCode: product?.productCode ?? '',
    productName: product?.productName ?? '',
    description: product?.description ?? '',
    accountType: product?.accountType ?? 'UNSECURED',
    rateType: product?.rateType ?? 'FIXED',
    dayCount: product ? dayEnum(product.dayCountConvention) : 'ACT_365',
    benchmark: product?.benchmarkRateRef ?? '',
    defaultRate: txt(product?.defaultInterestRate),
    defaultSpread: txt(product?.defaultSpread),
    minRate: txt(product?.minInterestRate), maxRate: txt(product?.maxInterestRate),
    minSpread: txt(product?.minSpread), maxSpread: txt(product?.maxSpread),
    minAmount: txt(product?.minSanctionedAmount), maxAmount: txt(product?.maxSanctionedAmount),
    minTenure: txt(product?.minTenureMonths), maxTenure: txt(product?.maxTenureMonths),
    status: product?.status ?? 'ACTIVE',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const first = useRef<HTMLInputElement>(null);
  const floating = f.rateType === 'FLOATING';

  useEffect(() => {
    first.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const set = (k: keyof typeof f, v: string) => setF(p => ({ ...p, [k]: v }));

  function body(): ProductInput {
    return {
      productCode: f.productCode.trim(),
      productName: f.productName.trim(),
      description: f.description.trim() || null,
      accountType: f.accountType,
      rateType: f.rateType,
      scheduleType: 'EMI',
      dayCountConvention: f.dayCount,
      benchmarkRateRef: floating ? (f.benchmark.trim() || null) : null,
      defaultInterestRate: num(f.defaultRate),
      defaultSpread: floating ? num(f.defaultSpread) : null,
      minInterestRate: num(f.minRate), maxInterestRate: num(f.maxRate),
      minSpread: floating ? num(f.minSpread) : null, maxSpread: floating ? num(f.maxSpread) : null,
      minSanctionedAmount: num(f.minAmount), maxSanctionedAmount: num(f.maxAmount),
      minTenureMonths: num(f.minTenure), maxTenureMonths: num(f.maxTenure),
      status: f.status,
      expectedVersion: product ? product.version : null,
    };
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      const r = isNew
        ? await send<{ message: string }>('POST', '/products', body())
        : await send<{ message: string }>('PUT', `/products/${encodeURIComponent(product!.productCode)}`, body());
      onSaved(`${f.productCode.trim()}: ${r.message}`);
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  const field = (k: keyof typeof f, text: string, o: { type?: string; disabled?: boolean; hint?: string; wide?: boolean; step?: string } = {}) => (
    <label className={`field ${o.wide ? 'wide' : ''}`}>
      <span>{text}</span>
      <input
        ref={k === 'productCode' || (k === 'productName' && !isNew) ? first : undefined}
        type={o.type ?? 'text'} step={o.step} disabled={o.disabled}
        value={f[k]} onChange={e => set(k, e.target.value)}
      />
      {o.hint && <span className="hint">{o.hint}</span>}
    </label>
  );
  const pick = (k: keyof typeof f, text: string, options: [string, string][], hint?: string) => (
    <label className="field">
      <span>{text}</span>
      <select value={f[k]} onChange={e => set(k, e.target.value)}>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
  const pct = { type: 'number', step: '0.0001' };

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label={isNew ? 'New product' : `Edit ${product!.productCode}`} onSubmit={submit}>
        <h2>{isNew ? 'New loan product' : <span className="mono">{product!.productCode}</span>}</h2>
        <p className="muted">
          {isNew
            ? 'Leave a limit empty for no limit.'
            : `${product!.accountCount} account${product!.accountCount === 1 ? '' : 's'} already opened on this product keep their current terms. Leave a limit empty for no limit.`}
        </p>
        <div className="form-grid">
          {field('productCode', 'Product code (up to 10 characters)', { disabled: !isNew, hint: isNew ? 'Letters, digits, - and _. Cannot be changed later.' : undefined })}
          {field('productName', 'Product name')}
          {field('description', 'Description', { wide: true })}
          {pick('accountType', 'Account type', [['UNSECURED', 'Unsecured'], ['MORTGAGE', 'Mortgage']])}
          {pick('rateType', 'Rate type', [['FIXED', 'Fixed'], ['FLOATING', 'Floating']])}
          {pick('dayCount', 'Day-count convention', DAY_COUNTS.map(([v, l]) => [v, l] as [string, string]))}
          <label className="field">
            <span>Schedule type</span>
            <select value="EMI" disabled><option value="EMI">EMI (reducing balance)</option></select>
            <span className="hint">The only schedule type available.</span>
          </label>
          {field('defaultRate', 'Default interest rate (% a year)', pct)}
          {floating && field('benchmark', 'Benchmark', { hint: 'For example REPO, MCLR_1Y.' })}
          {floating && field('defaultSpread', 'Default spread (%)', pct)}
          {field('minRate', 'Rate band: minimum (%)', pct)}
          {field('maxRate', 'Rate band: maximum (%)', pct)}
          {floating && field('minSpread', 'Spread band: minimum (%)', pct)}
          {floating && field('maxSpread', 'Spread band: maximum (%)', pct)}
          {field('minAmount', 'Sanctioned amount: minimum', { type: 'number' })}
          {field('maxAmount', 'Sanctioned amount: maximum', { type: 'number' })}
          {field('minTenure', 'Tenure: minimum (months)', { type: 'number' })}
          {field('maxTenure', 'Tenure: maximum (months)', { type: 'number' })}
          {pick('status', 'Status', [['ACTIVE', 'Active'], ['INACTIVE', 'Inactive']], 'Inactive products cannot be used for new accounts.')}
        </div>
        {err && <div className="banner banner-bad" role="alert">{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving...' : isNew ? 'Create product' : 'Save product'}</button>
        </div>
      </form>
    </div>
  );
}
