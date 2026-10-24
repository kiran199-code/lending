import { FormEvent, ReactNode, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { send } from '../api';
import { ChitGate } from '../components/chit';
import { ActingAsBar } from '../components/limits';
import { ConfirmModal, Field, addMonths, useIdempotencyKey } from '../components/ops';
import { Card, PageHeader } from '../components/ui';
import { date, money, todayIso } from '../format';
import type { ChitGroup, ChitPreview } from '../types';

interface Form {
  groupCode: string; name: string; foremanRef: string; chitValue: string; ticketCount: string; instalmentDueDay: string; auctionDay: string;
  startDate: string; commissionPct: string; maxCommissionPct: string; minDiscountPct: string; maxDiscountPct: string;
  subscription: string; enrolmentFee: string; registrationFee: string; securityDepositPct: string; extra: string;
}

/** The smoke-test group: two tickets, no fees, first instalment due on the 5th of next month. */
function initial(): Form {
  const first = `${addMonths(todayIso().slice(0, 8) + '01', 1)}`;
  return {
    groupCode: '', name: '', foremanRef: 'FOREMAN-1', chitValue: '20000', ticketCount: '2', instalmentDueDay: '5', auctionDay: '10',
    startDate: first, commissionPct: '5', maxCommissionPct: '5', minDiscountPct: '5', maxDiscountPct: '40',
    subscription: '', enrolmentFee: '', registrationFee: '', securityDepositPct: '', extra: '',
  };
}

export default function ChitGroupNew() {
  return (
    <>
      <PageHeader title="New chit group"
        sub="Creates the group in DRAFT. Open enrolment, enrol the tickets, then activate it from the group page." />
      <ChitGate><FormBody /></ChitGate>
    </>
  );
}

function FormBody() {
  const nav = useNavigate();
  const [f, setF] = useState<Form>(initial);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ChitPreview | null>(null);
  const [checking, setChecking] = useState(false);
  const keys = useIdempotencyKey();
  const set = (k: keyof Form) => (e: { target: { value: string } }) => { setF({ ...f, [k]: e.target.value }); setPreview(null); };

  /** The rules object, or a message saying what is wrong with the form. */
  function rules(): Record<string, unknown> | string {
    let extra: Record<string, unknown> = {};
    if (f.extra.trim()) {
      try {
        const parsed: unknown = JSON.parse(f.extra);
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return 'Other rules must be a JSON object, for example {"graceDays": 3}';
        extra = parsed as Record<string, unknown>;
      } catch {
        return 'Other rules is not valid JSON';
      }
    }
    const num = (k: keyof Form, name: string): number | string => {
      const v = f[k].trim();
      if (v === '' || isNaN(Number(v))) return `${name} must be a number`;
      return Number(v);
    };
    const r: Record<string, unknown> = {};
    const required: [keyof Form, string][] = [
      ['chitValue', 'Chit value'], ['ticketCount', 'Tickets'], ['instalmentDueDay', 'Instalment due day'], ['auctionDay', 'Auction day'],
      ['commissionPct', 'Commission %'], ['maxCommissionPct', 'Largest commission %'], ['minDiscountPct', 'Smallest bid discount %'],
      ['maxDiscountPct', 'Largest bid discount %'],
    ];
    for (const [k, n] of required) {
      const v = num(k, n);
      if (typeof v === 'string') return v;
      r[k] = v;
    }
    if (!f.startDate) return 'Start date is required';
    r.startDate = f.startDate;
    for (const [k, n] of [['subscription', 'Subscription'], ['enrolmentFee', 'Enrolment fee'], ['registrationFee', 'Registration fee'], ['securityDepositPct', 'Security deposit %']] as [keyof Form, string][]) {
      if (f[k].trim() !== '') {
        const v = num(k, n);
        if (typeof v === 'string') return v;
        r[k] = v;
      }
    }
    return { ...r, ...extra };
  }

  function body(): Record<string, unknown> | string {
    const r = rules();
    if (typeof r === 'string') return r;
    return { groupCode: f.groupCode.trim(), name: f.name.trim(), foremanRef: f.foremanRef.trim(), rules: r };
  }

  function validate(): string | null {
    if (!/^[A-Za-z0-9_.:-]{1,64}$/.test(f.groupCode.trim())) return 'Group code may contain only letters, digits and . _ : - (up to 64)';
    if (!f.name.trim()) return 'Name is required';
    if (!f.foremanRef.trim()) return 'Foreman reference is required';
    return null;
  }

  async function check() {
    setError(null);
    const r = rules();
    if (typeof r === 'string') return setError(r);
    setChecking(true);
    try {
      setPreview(await send<ChitPreview>('POST', '/chit/groups/preview', { rules: r }));
    } catch (e) {
      setPreview(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setChecking(false);
    }
  }

  function review(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const bad = validate();
    if (bad) return setError(bad);
    const b = body();
    if (typeof b === 'string') return setError(b);
    setConfirming(true);
  }

  async function create() {
    const b = body();
    if (typeof b === 'string') throw new Error(b);
    const r = await send<ChitGroup>('POST', '/chit/groups', b, undefined, { 'Idempotency-Key': keys.keyFor(JSON.stringify(b)) });
    keys.done();
    nav(`/chit/groups/${encodeURIComponent(r.groupCode)}`);
  }

  const rows: [string, ReactNode][] = [
    ['Group code', f.groupCode.trim()], ['Name', f.name.trim()], ['Foreman', f.foremanRef.trim()],
    ['Chit value', money(Number(f.chitValue))], ['Tickets', f.ticketCount], ['Starts', date(f.startDate)],
    ['Instalment due', `day ${f.instalmentDueDay}; auction on day ${f.auctionDay}`],
  ];

  return (
    <>
      <ActingAsBar needed={false} />
      <Card title="Group details">
        <form onSubmit={review}>
          <div className="form-grid">
            <Field label="Group code" hint="Letters, digits and . _ : -; cannot be changed"><input value={f.groupCode} onChange={set('groupCode')} /></Field>
            <Field label="Name"><input value={f.name} onChange={set('name')} /></Field>
            <Field label="Foreman reference" hint="Who runs the chit"><input value={f.foremanRef} onChange={set('foremanRef')} /></Field>
            <Field label="Chit value" hint="The pot each cycle"><input type="number" step="0.01" min="0" value={f.chitValue} onChange={set('chitValue')} /></Field>
            <Field label="Tickets" hint="Members; also the number of cycles unless set under other rules"><input inputMode="numeric" value={f.ticketCount} onChange={set('ticketCount')} /></Field>
            <Field label="Start date" hint="Must not be before the business date; Check the rules shows the schedule"><input type="date" value={f.startDate} onChange={set('startDate')} /></Field>
            <Field label="Instalment due day" hint="Day of the month"><input inputMode="numeric" value={f.instalmentDueDay} onChange={set('instalmentDueDay')} /></Field>
            <Field label="Auction day" hint="Day of the month"><input inputMode="numeric" value={f.auctionDay} onChange={set('auctionDay')} /></Field>
            <Field label="Commission %"><input type="number" step="0.01" min="0" value={f.commissionPct} onChange={set('commissionPct')} /></Field>
            <Field label="Largest commission %"><input type="number" step="0.01" min="0" value={f.maxCommissionPct} onChange={set('maxCommissionPct')} /></Field>
            <Field label="Smallest bid discount %"><input type="number" step="0.01" min="0" value={f.minDiscountPct} onChange={set('minDiscountPct')} /></Field>
            <Field label="Largest bid discount %"><input type="number" step="0.01" min="0" value={f.maxDiscountPct} onChange={set('maxDiscountPct')} /></Field>
            <Field label="Subscription" hint="Each member's instalment. Leave empty to use chit value / tickets (only when that divides exactly). Chit value must equal subscription x tickets">
              <input type="number" step="0.01" min="0" value={f.subscription} onChange={set('subscription')} />
            </Field>
            <Field label="Enrolment fee" hint="Leave empty for none"><input type="number" step="0.01" min="0" value={f.enrolmentFee} onChange={set('enrolmentFee')} /></Field>
            <Field label="Registration fee" hint="Leave empty for none"><input type="number" step="0.01" min="0" value={f.registrationFee} onChange={set('registrationFee')} /></Field>
            <Field label="Security deposit %" hint={'Of the chit value. Leave empty unless "securityRule" is DEPOSIT or LIEN_ON_DEPOSIT (set that under other rules)'}><input type="number" step="0.01" min="0" value={f.securityDepositPct} onChange={set('securityDepositPct')} /></Field>
          </div>
          <Field label="Other rules (JSON, optional)" wide
            hint='Anything else in the rules, for example {"graceDays": 3, "securityRule": "GUARANTOR"}. These override the fields above. Anything not given takes the service default.'>
            <textarea rows={3} value={f.extra} onChange={set('extra')} spellCheck={false} />
          </Field>
          {error && <div className="banner banner-bad" role="alert">{error}</div>}
          <p>
            <button type="button" className="btn" onClick={check} disabled={checking}>{checking ? 'Checking...' : 'Check the rules'}</button>{' '}
            <button className="btn btn-primary">Review and create</button>
          </p>
        </form>
      </Card>
      {preview && <PreviewCard p={preview} />}
      {confirming && (
        <ConfirmModal title="Create this group?" rows={rows} confirmLabel="Create group"
          note="It starts as DRAFT with these rules. Money terms stay editable only until the first ticket is enrolled."
          onConfirm={create} onClose={() => setConfirming(false)} />
      )}
    </>
  );
}

function PreviewCard({ p }: { p: ChitPreview }) {
  return (
    <Card title="Rule check">
      <div className={`banner ${p.valid ? 'banner-good' : 'banner-bad'}`} role="status">
        {p.valid ? 'The rules reconcile.' : 'The rules do not reconcile yet.'}
      </div>
      {p.violations.length > 0 && (
        <ul>{p.violations.map((v, i) => <li key={i}><strong>{v.code}</strong>{v.field ? ` (${v.field})` : ''}: {v.message}</li>)}</ul>
      )}
      {p.scheduleError && <div className="banner banner-warn" role="alert">Schedule could not be built: {p.scheduleError}</div>}
      {p.schedule && p.schedule.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Cycle</th><th>Instalment due</th><th>Auction</th></tr></thead>
            <tbody>{p.schedule.map(c => <tr key={c.cycleNo}><td>{c.cycleNo}</td><td>{date(c.dueDate)}</td><td>{date(c.auctionDate)}</td></tr>)}</tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
