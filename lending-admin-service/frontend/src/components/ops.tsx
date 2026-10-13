import { FormEvent, ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError, get } from '../api';
import { date, money } from '../format';
import { useLoad } from '../hooks';
import type { AccountDetail, BusinessDateView } from '../types';
import { Card, KeyValues, StatusBadge } from './ui';

/** A fresh idempotency key; crypto.randomUUID needs a secure context, so fall back for plain http. */
export function newKey(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * One key per distinct request: the same form values keep the same key, so a double click or a retry after a lost
 * answer is applied once by lending-service; changing a value, or finishing the request, starts a new one.
 */
export function useIdempotencyKey() {
  const ref = useRef<{ fp: string; key: string } | null>(null);
  return {
    keyFor(fingerprint: string): string {
      if (!ref.current || ref.current.fp !== fingerprint) ref.current = { fp: fingerprint, key: newKey() };
      return ref.current.key;
    },
    done() { ref.current = null; },
  };
}

/** Last day of the month is kept when the target month is shorter (31 Jan + 1 month = 28/29 Feb). */
export function addMonths(iso: string, months: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const total = (m - 1) + months;
  const ny = y + Math.floor(total / 12);
  const nm = ((total % 12) + 12) % 12;
  const last = new Date(Date.UTC(ny, nm + 1, 0)).getUTCDate();
  const day = Math.min(d, last);
  return `${ny}-${String(nm + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

export function useBusinessDate() {
  return useLoad(() => get<BusinessDateView>('/business-date'), []);
}

/** Shows the business date every operation is dated against, and warns while end of day is running. */
export function BusinessDateBar({ bd }: { bd: BusinessDateView | null }) {
  if (!bd) return <div className="banner" role="status">Reading the business date...</div>;
  const running = bd.status === 'EOD_RUNNING';
  return (
    <div className={`banner ${running ? 'banner-warn' : ''}`} role="status">
      Business date: <strong>{date(bd.businessDate)}</strong>
      {running
        ? ' - end of day is running. Operations on accounts are best left until it finishes.'
        : bd.fromLending ? ' - open.' : ' - lending-service has not set it yet; this is the wall-clock date.'}
    </div>
  );
}

export type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

/** Finds one account by account number (or internal id) and keeps its figures current. */
export function useAccount() {
  const [sp] = useSearchParams();
  const [account, setAccount] = useState<AccountDetail | null>(null);
  const [query, setQuery] = useState(sp.get('account') ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const find = useCallback(async (q: string) => {
    const text = q.trim();
    if (!text) { setError('Enter an account number'); return; }
    setBusy(true);
    setError(null);
    try {
      let found: AccountDetail;
      try {
        found = await get<AccountDetail>(`/accounts/by-number/${encodeURIComponent(text)}`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404 && /^\d+$/.test(text)) found = await get<AccountDetail>(`/accounts/${text}`);
        else throw e;
      }
      setAccount(found);
    } catch (e) {
      setAccount(null);
      setError(e instanceof ApiError && e.status === 404 ? `No account found for "${text}"` : e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    const q = sp.get('account');
    if (q) void find(q);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = useCallback(() => (account ? find(account.accountNumber) : Promise.resolve()), [account, find]);
  const clear = useCallback(() => { setAccount(null); setError(null); }, []);
  return { account, query, setQuery, busy, error, find, refresh, clear };
}

export function AccountFinder({ finder, label = 'Account number', hint }: {
  finder: ReturnType<typeof useAccount>; label?: string; hint?: ReactNode;
}) {
  function submit(e: FormEvent) { e.preventDefault(); void finder.find(finder.query); }
  return (
    <Card title="Account">
      <form className="inline-form" onSubmit={submit}>
        <input value={finder.query} onChange={e => finder.setQuery(e.target.value)} placeholder={label} aria-label={label} />
        <button className="btn btn-primary" disabled={finder.busy}>{finder.busy ? 'Finding...' : 'Find account'}</button>
      </form>
      {hint && <p className="muted" style={{ marginBottom: 0 }}>{hint}</p>}
      {finder.error && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{finder.error}</div>}
    </Card>
  );
}

export function AccountFacts({ a }: { a: AccountDetail }) {
  const f = a.figures;
  return (
    <Card title={<>Account <span className="mono">{a.accountNumber}</span></>} actions={<Link className="btn" to={`/accounts/${a.accountId}`}>Open full inquiry</Link>}>
      <KeyValues items={[
        ['Status', <StatusBadge status={a.accountStatus} />],
        ['Product', `${a.productCode} (${a.accountType === 'MORTGAGE' ? 'Mortgage' : 'Unsecured'})`],
        ['Customer', a.customerId],
        ['Sanctioned', money(a.sanctionedAmount)],
        ['Disbursed', money(a.disbursedAmount)],
        ['EMI', money(a.emiAmount)],
        ['Outstanding principal', money(f.outstandingPrincipal)],
        ['Next instalment', f.nextDueDate ? `${date(f.nextDueDate)} - ${money(f.nextEmiAmount)}` : '-'],
        ['Overdue', f.overdueInstallments > 0 ? `${f.overdueInstallments} instalment(s), ${money(f.overdueAmount)}, ${f.daysPastDue} days` : 'None'],
      ]} />
    </Card>
  );
}

/**
 * The confirmation step: shows the business date and what is about to happen. `onConfirm` does the call and closes the
 * dialog itself on success; anything it throws is shown here and the dialog stays open so the user can retry or cancel.
 * `typeToConfirm` makes the user type a word first, for actions that cannot be undone.
 */
export function ConfirmModal({ title, businessDate, rows, note, confirmLabel, danger, typeToConfirm, onConfirm, onClose }: {
  title: string; businessDate?: string; rows: [string, ReactNode][]; note?: ReactNode; confirmLabel: string;
  danger?: boolean; typeToConfirm?: string; onConfirm: () => Promise<void>; onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [typed, setTyped] = useState('');
  const ready = !typeToConfirm || typed === typeToConfirm;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || busy) return;
    setBusy(true);
    setErr(null);
    try {
      await onConfirm();
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label={title} onSubmit={submit}>
        <h2>{title}</h2>
        {businessDate && <p style={{ fontSize: 16 }}>Business date: <strong>{date(businessDate)}</strong></p>}
        <KeyValues items={rows} />
        {note && <p style={{ marginBottom: 0 }}>{note}</p>}
        {typeToConfirm && (
          <label className="field wide" style={{ marginTop: 12 }}>
            <span>Type {typeToConfirm} to confirm</span>
            <input value={typed} onChange={e => setTyped(e.target.value)} autoFocus aria-label={`Type ${typeToConfirm} to confirm`} />
          </label>
        )}
        {err && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className={danger ? 'btn btn-danger' : 'btn btn-primary'} disabled={busy || !ready}>
            {busy ? 'Working...' : confirmLabel}
          </button>
        </div>
      </form>
    </div>
  );
}

export function Field({ label, hint, wide, children }: { label: string; hint?: ReactNode; wide?: boolean; children: ReactNode }) {
  return (
    <label className={wide ? 'field wide' : 'field'}>
      <span>{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

export const CHANNELS: [string, string][] = [['MANUAL', 'Manual'], ['ONLINE', 'Online'], ['NACH', 'NACH (auto-debit)']];

export function ChannelSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <select value={value} onChange={e => onChange(e.target.value)}>
      {CHANNELS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}
    </select>
  );
}

export const payable = (status: string) => status === 'ACTIVE' || status.startsWith('NPA_');
