import { ReactNode, useState, useSyncExternalStore } from 'react';
import { get, getActingAs, setActingAs, subscribeActingAs } from '../api';
import { useAuth } from '../auth';
import { label, money, Tone } from '../format';
import { useLoad } from '../hooks';
import type { Availability, Facility } from '../types';
import { ConfirmModal, Notice } from './ops';
import { Badge, Card, ErrorBox, Loading } from './ui';

// ------------------------------------------------------------------ badges and small helpers

export function facilityTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': return 'good';
    case 'DRAFT': return 'info';
    case 'SUSPENDED': return 'warn';
    case 'FROZEN': case 'EXPIRED': return 'bad';
    default: return 'neutral'; // CLOSED
  }
}

/** One tone mapping for every status word the limit screens show (changes, valuations, allocations, breaches). */
export function statusTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': case 'APPROVED': case 'VERIFIED': case 'CLOSED_OK': return 'good';
    case 'PENDING': case 'DRAFT': return 'info';
    case 'ACKNOWLEDGED': case 'SUSPENDED': return 'warn';
    case 'OPEN': case 'FAILED': case 'FROZEN': case 'EXPIRED': return 'bad';
    default: return 'neutral'; // REJECTED, RELEASED, CLOSED
  }
}

export function FacilityBadge({ status }: { status: string }) {
  return <Badge tone={facilityTone(status)}>{label(status)}</Badge>;
}

export function StateBadge({ status }: { status: string }) {
  return <Badge tone={statusTone(status)}>{label(status)}</Badge>;
}

export function NoticeBanner({ notice }: { notice: Notice }) {
  if (!notice) return null;
  return <div className={`banner banner-${notice.tone}`} role={notice.tone === 'bad' ? 'alert' : 'status'}>{notice.text}</div>;
}

/** Runs one write call at a time and keeps the outcome as a banner message. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  async function run<T>(fn: () => Promise<T>, ok: (r: T) => string, after?: (r: T) => void): Promise<boolean> {
    if (busy) return false;
    setBusy(true);
    setNotice(null);
    try {
      const r = await fn();
      setNotice({ tone: 'good', text: ok(r) });
      after?.(r);
      return true;
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
      return false;
    } finally {
      setBusy(false);
    }
  }
  return { busy, notice, setNotice, run };
}

export const FACILITY_TYPES: [string, string][] = [
  ['OVERALL', 'Overall limit (top of a hierarchy)'], ['SUBLIMIT', 'Sub-limit (inside another facility)'], ['PRODUCT', 'Product limit (loans are drawn here)'],
];

/** Change types that can still apply to a facility in this status; the service re-checks every one. */
export function changeTypesFor(status: string): string[] {
  switch (status) {
    case 'DRAFT': return ['ACTIVATE', 'AMEND_LIMIT', 'EXTEND_EXPIRY'];
    case 'ACTIVE': return ['AMEND_LIMIT', 'EXTEND_EXPIRY', 'SUSPEND', 'FREEZE', 'CLOSE'];
    case 'SUSPENDED': return ['REINSTATE', 'FREEZE', 'AMEND_LIMIT', 'EXTEND_EXPIRY', 'CLOSE'];
    case 'FROZEN': return ['REINSTATE', 'AMEND_LIMIT', 'EXTEND_EXPIRY', 'CLOSE'];
    case 'EXPIRED': return ['EXTEND_EXPIRY', 'CLOSE'];
    default: return [];
  }
}

export const UNMET_TEXT: Record<string, string> = {
  LEGAL_NOT_VERIFIED: 'Legal verification has not been recorded as VERIFIED',
  NO_CURRENT_VALUATION: 'No approved valuation that is still within its validity period',
  CHARGE_NOT_REGISTERED: 'Charge is not registered',
  INSURANCE_MISSING_OR_EXPIRED: 'Insurance is missing or has expired',
};

export function readPayload(payload: string | null): { newAmount?: string; newExpiry?: string } {
  if (!payload) return {};
  try { return JSON.parse(payload) as { newAmount?: string; newExpiry?: string }; } catch { return {}; }
}

export function describeChange(changeType: string, payload: string | null): string {
  const p = readPayload(payload);
  if (changeType === 'AMEND_LIMIT' && p.newAmount) return `${label(changeType)} to ${money(Number(p.newAmount))}`;
  if (changeType === 'EXTEND_EXPIRY' && p.newExpiry) return `${label(changeType)} to ${p.newExpiry}`;
  return label(changeType);
}

/** The name that will be recorded as maker or checker: the one typed as "acting as", else the login name. Re-renders on change. */
export function useActingAs(): string {
  const { me } = useAuth();
  const typed = useSyncExternalStore(subscribeActingAs, getActingAs);
  return typed.trim() || me?.username || '';
}

export const validPerson = (v: string) => /^[A-Za-z0-9._@-]{2,40}$/.test(v.trim());

// ------------------------------------------------------------------ acting as

/**
 * The console has one login, so the person says who they are acting as. LCM compares these names for maker-checker
 * (the proposer and the approver must differ). The names are typed text, not verified identities.
 */
export function ActingAsBar({ needed = true }: { needed?: boolean }) {
  const { me } = useAuth();
  const [name, setName] = useState(getActingAs());
  const bad = name.trim() !== '' && !validPerson(name);
  return (
    <Card title="Acting as">
      <div className="filters">
        <input value={name} placeholder={me?.username ? `Defaults to ${me.username}` : 'Your name'} aria-label="Acting as"
          onChange={e => { setName(e.target.value); setActingAs(e.target.value); }} />
        <span className="muted">
          {needed
            ? 'The name recorded as maker or checker. A change must be approved under a different name from the one that proposed it.'
            : 'The name recorded against what you do here.'}
        </span>
      </div>
      {bad && <div className="banner banner-bad" role="alert">Use 2 to 40 letters, digits, dot, dash, underscore or @.</div>}
      <p className="muted" style={{ marginBottom: 0 }}>
        These names are typed, not verified: the console has a single login, so one person could type two names. Treat the check as a
        reminder of the procedure until sign-in by person (IAM) is in place.
      </p>
    </Card>
  );
}

// ------------------------------------------------------------------ configured?

export function LimitsGate({ children }: { children: ReactNode }) {
  const st = useLoad(() => get<{ enabled: boolean }>('/limits/status'), []);
  if (st.loading && !st.data) return <Loading what="Checking limit service" />;
  if (st.error) return <ErrorBox message={st.error} onRetry={st.reload} />;
  if (st.data && !st.data.enabled) {
    return (
      <div className="banner banner-warn" role="alert">
        The limit and collateral screens are not set up. Set LCM_SERVICE_URL and LCM_SERVICE_PASSWORD on the admin service
        (the password is the one lending-lcm-service uses for LCM_SERVICE_PASSWORD) and restart it.
      </div>
    );
  }
  return <>{children}</>;
}

// ------------------------------------------------------------------ facility picker (loan screens)

const validCustomer = (v: string) => /^\d+$/.test(v.trim());

/** What is left on a facility, shown under any facility choice. */
export function AvailabilityLine({ a, amount }: { a: Availability; amount?: number }) {
  const short = amount !== undefined && amount > 0 && amount > a.effectiveAvailable;
  return (
    <div className={short ? 'banner banner-warn' : 'muted'} role={short ? 'status' : undefined} style={{ marginBottom: 0 }}>
      Status {label(a.status)}. Available now <strong>{money(a.effectiveAvailable)}</strong> {a.currencyCode}
      {' '}(limit {money(a.effectiveLimit)}, used {money(a.consumed)}).
      {short && <> The loan amount is more than this, so lending-service will refuse it at creation when its limit check is in live mode.</>}
    </div>
  );
}

/**
 * Chooses the facility a loan draws on. Lists the customer's ACTIVE facilities; when the limit service is not set up, or
 * the facility is not in the list, a reference can still be typed (lending-service validates it either way).
 */
export function FacilityPicker({ customerId, value, onChange, amount, disabled }: {
  customerId: string; value: string; onChange: (v: string) => void; amount?: number; disabled?: boolean;
}) {
  const cid = customerId.trim();
  const st = useLoad(() => get<{ enabled: boolean }>('/limits/status'), []);
  const list = useLoad(
    () => (validCustomer(cid) && st.data?.enabled
      ? get<Facility[]>('/limits/facilities', { customerId: cid, status: 'ACTIVE' })
      : Promise.resolve([] as Facility[])),
    [cid, st.data?.enabled],
  );
  const [manual, setManual] = useState(false);
  const ref = value.trim();
  const avail = useLoad(
    () => (ref !== '' && st.data?.enabled ? get<Availability>(`/limits/facilities/${encodeURIComponent(ref)}/availability`) : Promise.resolve(null)),
    [ref, st.data?.enabled],
  );

  const facilities = list.data ?? [];
  const inList = facilities.some(f => f.facilityRef === ref);
  const typing = manual || !st.data?.enabled || list.error !== null || (ref !== '' && !inList && !list.loading);

  return (
    <div className="field wide">
      <span>Limit facility (optional)</span>
      {!typing ? (
        <select value={ref} disabled={disabled} onChange={e => { if (e.target.value === '__other') { setManual(true); onChange(''); } else onChange(e.target.value); }}
          aria-label="Limit facility">
          <option value="">{validCustomer(cid) ? (list.loading ? 'Loading facilities...' : facilities.length ? 'No facility (not limit-controlled)' : 'No active facility for this customer') : 'Enter the customer id first'}</option>
          {facilities.map(f => (
            <option key={f.facilityRef} value={f.facilityRef}>
              {f.facilityRef} - {label(f.facilityType)}{f.productCode ? ` ${f.productCode}` : ''} - limit {money(f.sanctionedAmount)} {f.currencyCode}
            </option>
          ))}
          <option value="__other">Type a reference instead...</option>
        </select>
      ) : (
        <div className="inline-form">
          <input value={value} disabled={disabled} onChange={e => onChange(e.target.value)} placeholder="Facility reference, e.g. SANC-1001" aria-label="Limit facility reference" style={{ width: '100%' }} />
          {st.data?.enabled && !list.error && <button type="button" className="btn" onClick={() => { setManual(false); onChange(''); }}>Pick from list</button>}
        </div>
      )}
      {list.error && <span className="hint">Could not list facilities ({list.error}); type the reference.</span>}
      {st.data && !st.data.enabled && <span className="hint">The limit service is not set up on this console, so the reference cannot be checked here. lending-service still checks it.</span>}
      {ref !== '' && avail.data && <AvailabilityLine a={avail.data} amount={amount} />}
      {ref !== '' && avail.error && <span className="hint">Availability not shown: {avail.error}</span>}
      <span className="hint">Leave empty for a loan that is not drawn on a limit. Whether an unlinked or over-limit loan is refused depends on the lending.lcm.mode setting (off, shadow or live).</span>
    </div>
  );
}

// ------------------------------------------------------------------ approve / reject dialog

/** Confirmation with an optional note, used for approving or rejecting. */
export function DecisionModal({ title, rows, note, setNote, confirmLabel, danger, onConfirm, onClose }: {
  title: string; rows: [string, ReactNode][]; note: string; setNote: (v: string) => void; confirmLabel: string; danger?: boolean;
  onConfirm: () => Promise<void>; onClose: () => void;
}) {
  return (
    <ConfirmModal title={title} rows={rows} confirmLabel={confirmLabel} danger={danger} onConfirm={onConfirm} onClose={onClose}
      note={<label className="field wide"><span>Note (optional)</span><input value={note} onChange={e => setNote(e.target.value)} aria-label="Decision note" /></label>} />
  );
}
