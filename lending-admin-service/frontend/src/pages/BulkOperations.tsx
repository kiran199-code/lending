import { useState } from 'react';
import { send } from '../api';
import { BusinessDateBar, ConfirmModal, Field, Notice, useBusinessDate, addMonths } from '../components/ops';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, PageHeader, Tabs } from '../components/ui';
import { date } from '../format';
import type { BulkItemResult, BulkResult } from '../types';

type Mode = 'create' | 'disburse';
const MAX = 200;

const CREATE_HELP = 'customerId, sanctionRefId, productCode, sanctionedAmount, tenureMonths, interestRate (optional)';
const CREATE_SAMPLE = '1001, 7001, PL01, 500000, 36\n1002, 7002, AL01, 900000, 48, 9.25';
const DISBURSE_HELP = 'accountId, disbursedAmount (optional: blank = full), firstEmiDueDate (optional: blank = the default below)';
const DISBURSE_SAMPLE = '1\n2, 250000\n3, , 2026-12-10';

/** One item per non-empty line; a first line that does not start with a number is treated as a header and skipped. */
function lines(text: string): string[][] {
  const rows = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean).map(l => l.split(/[,\t;]/).map(c => c.trim()));
  if (rows.length && !/^\d/.test(rows[0][0])) rows.shift();
  return rows;
}

function parseCreate(text: string): { items: Record<string, unknown>[]; error: string | null } {
  const items: Record<string, unknown>[] = [];
  const rows = lines(text);
  for (let i = 0; i < rows.length; i++) {
    const [cust, ref, code, amt, tenure, rate] = rows[i];
    const n = i + 1;
    if (!/^\d+$/.test(cust ?? '')) return { items, error: `Line ${n}: customerId must be a whole number` };
    if (!/^\d+$/.test(ref ?? '')) return { items, error: `Line ${n}: sanctionRefId must be a whole number` };
    if (!code) return { items, error: `Line ${n}: productCode is missing` };
    if (!(Number(amt) > 0)) return { items, error: `Line ${n}: sanctionedAmount must be greater than zero` };
    if (!/^[1-9]\d*$/.test(tenure ?? '')) return { items, error: `Line ${n}: tenureMonths must be a whole number` };
    const item: Record<string, unknown> = { customerId: Number(cust), sanctionRefId: Number(ref), productCode: code, sanctionedAmount: Number(amt), tenureMonths: Number(tenure) };
    if (rate) {
      if (isNaN(Number(rate))) return { items, error: `Line ${n}: interestRate must be a number` };
      item.interestRate = Number(rate);
    }
    items.push(item);
  }
  return { items, error: null };
}

function parseDisburse(text: string, defaultDue: string): { items: Record<string, unknown>[]; error: string | null } {
  const items: Record<string, unknown>[] = [];
  const rows = lines(text);
  for (let i = 0; i < rows.length; i++) {
    const [id, amt, due] = rows[i];
    const n = i + 1;
    if (!/^\d+$/.test(id ?? '')) return { items, error: `Line ${n}: accountId must be a whole number` };
    const item: Record<string, unknown> = { accountId: Number(id) };
    if (amt) {
      if (!(Number(amt) > 0)) return { items, error: `Line ${n}: disbursedAmount must be greater than zero` };
      item.disbursedAmount = Number(amt);
    }
    if (due) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(due)) return { items, error: `Line ${n}: firstEmiDueDate must look like 2026-12-10` };
      item.firstEmiDueDate = due;
    } else if (!defaultDue) {
      return { items, error: `Line ${n}: no first instalment date; fill in the default date below` };
    }
    items.push(item);
  }
  return { items, error: null };
}

const tone = (o: string) => (o === 'FAILED' ? 'bad' : o === 'ALREADY_EXISTS' || o === 'ALREADY_ACTIVE' ? 'warn' : 'good');

export default function BulkOperations() {
  const bd = useBusinessDate();
  const [mode, setMode] = useState<Mode>('create');
  const [text, setText] = useState('');
  const [defaultDue, setDefaultDue] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkResult | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  const suggested = bd.data ? addMonths(bd.data.businessDate, 1) : '';
  const parsed = text.trim() ? (mode === 'create' ? parseCreate(text) : parseDisburse(text, defaultDue)) : { items: [], error: null };

  function switchMode(m: Mode) { setMode(m); setText(''); setResult(null); setNotice(null); setError(null); }

  function check() {
    setError(null);
    if (!text.trim()) return setError('Paste at least one line');
    if (parsed.error) return setError(parsed.error);
    if (parsed.items.length > MAX) return setError(`At most ${MAX} lines at a time; this has ${parsed.items.length}`);
    setConfirming(true);
  }

  async function run() {
    const body = mode === 'create' ? { accounts: parsed.items } : { defaultFirstEmiDueDate: defaultDue || undefined, items: parsed.items };
    const r = await send<BulkResult>('POST', mode === 'create' ? '/operations/accounts/bulk/create' : '/operations/accounts/bulk/disburse', body);
    setResult(r);
    setConfirming(false);
    setNotice({ tone: r.failed === 0 ? 'good' : 'warn', text: `${r.succeeded} of ${r.total} succeeded${r.failed ? `, ${r.failed} failed (see the table)` : ''}.` });
  }

  const cols: Col<BulkItemResult>[] = [
    { key: 'i', header: 'Line', render: r => r.index + 1 },
    { key: 'o', header: 'Outcome', render: r => <Badge tone={tone(r.outcome)}>{r.outcome.replace(/_/g, ' ').toLowerCase()}</Badge> },
    { key: 'n', header: 'Account number', render: r => (r.accountNumber ? <strong className="mono">{r.accountNumber}</strong> : '-') },
    { key: 'id', header: 'Account id', render: r => r.accountId ?? '-' },
    { key: 's', header: 'Status', render: r => r.accountStatus ?? '-' },
    { key: 'm', header: 'Message', render: r => r.message ?? '' },
  ];

  return (
    <>
      <PageHeader title="Bulk create / disburse" sub="Up to 200 accounts at a time; each line succeeds or fails on its own" />
      <BusinessDateBar bd={bd.data} />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      <Tabs<Mode> tabs={[{ id: 'create', label: 'Create loans' }, { id: 'disburse', label: 'Disburse loans' }]} value={mode} onChange={switchMode} />
      <Card title={mode === 'create' ? 'Create loans' : 'Disburse loans'}>
        <p className="muted">One account per line: <span className="mono">{mode === 'create' ? CREATE_HELP : DISBURSE_HELP}</span>. A header line is allowed.
          {mode === 'create' && ' Repeating a sanctionRefId returns the existing account instead of creating another.'}</p>
        {mode === 'disburse' && (
          <div className="form-grid">
            <Field label="Default first instalment date" hint="Used for every line that does not give its own">
              <div className="inline-form">
                <input type="date" value={defaultDue} onChange={e => setDefaultDue(e.target.value)} />
                {suggested && <button type="button" className="btn" onClick={() => setDefaultDue(suggested)}>Use {date(suggested)}</button>}
              </div>
            </Field>
          </div>
        )}
        <label className="field wide">
          <span>Lines</span>
          <textarea rows={8} value={text} onChange={e => setText(e.target.value)} placeholder={mode === 'create' ? CREATE_SAMPLE : DISBURSE_SAMPLE}
            style={{ font: 'inherit', fontFamily: 'ui-monospace, Menlo, Consolas, monospace', padding: 10, border: '1px solid var(--line)', borderRadius: 8, background: 'var(--surface)', color: 'var(--ink)' }} />
          <span className="hint">{parsed.error ?? `${parsed.items.length} line${parsed.items.length === 1 ? '' : 's'} read`}</span>
        </label>
        {error && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{error}</div>}
        <p><button className="btn btn-primary" onClick={check}>Review and run</button></p>
      </Card>
      {result && (
        <Card title={`Result: ${result.succeeded} succeeded, ${result.failed} failed`}>
          <DataTable columns={cols} rows={result.results} rowKey={r => String(r.index)} />
        </Card>
      )}
      {confirming && (
        <ConfirmModal title={mode === 'create' ? 'Create these loan accounts?' : 'Disburse these accounts?'} businessDate={bd.data?.businessDate}
          rows={[['Lines', parsed.items.length], ...(mode === 'disburse' ? [['Default first instalment', defaultDue ? date(defaultDue) : 'none'] as [string, string]] : [])]}
          note={mode === 'create' ? 'Accounts are created pending disbursement.' : 'Each account is disbursed and its schedule generated. This cannot be undone from the console.'}
          confirmLabel={mode === 'create' ? `Create ${parsed.items.length}` : `Disburse ${parsed.items.length}`} onConfirm={run} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}
