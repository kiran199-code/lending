import { ReactNode } from 'react';
import { accountTone, label, Tone } from '../format';
import type { Page } from '../types';

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={accountTone(status)}>{label(status)}</Badge>;
}

export function InstallmentBadge({ status, daysPastDue }: { status: string; daysPastDue: number }) {
  if (status === 'PAID') return <Badge tone="good">Paid</Badge>;
  if (daysPastDue > 0) return <Badge tone="bad">Overdue {daysPastDue}d</Badge>;
  return <Badge tone="info">Pending</Badge>;
}

export function PaymentBadge({ status }: { status: string }) {
  const tone: Tone = status === 'SUCCESS' ? 'good' : status === 'FAILED' ? 'bad' : 'warn';
  return <Badge tone={tone}>{label(status)}</Badge>;
}

export function Kpi({ title, value, sub, tone }: { title: string; value: ReactNode; sub?: ReactNode; tone?: Tone }) {
  return (
    <div className={`kpi ${tone ? `kpi-${tone}` : ''}`}>
      <div className="kpi-title">{title}</div>
      <div className="kpi-value">{value}</div>
      {sub !== undefined && <div className="kpi-sub">{sub}</div>}
    </div>
  );
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-head">
          <h3>{title}</h3>
          <div className="card-actions">{actions}</div>
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p className="muted">{sub}</p>}
      </div>
      <div className="page-actions">{actions}</div>
    </div>
  );
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return <div className="state" role="status">{what}...</div>;
}

export function ErrorBox({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="state state-error" role="alert">
      <div>{message}</div>
      {onRetry && <button className="btn" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="state">{children}</div>;
}

export function Pager({ page, onPage }: { page: Page<unknown>; onPage: (p: number) => void }) {
  if (page.totalPages <= 1) {
    return <div className="pager muted">{page.total} {page.total === 1 ? 'row' : 'rows'}</div>;
  }
  const from = page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  return (
    <div className="pager">
      <span className="muted">{from}-{to} of {page.total}</span>
      <button className="btn" disabled={page.page === 0} onClick={() => onPage(page.page - 1)}>Previous</button>
      <span>Page {page.page + 1} of {page.totalPages}</span>
      <button className="btn" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { id: T; label: string }[]; value: T; onChange: (t: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map(t => (
        <button key={t.id} role="tab" aria-selected={t.id === value} className={t.id === value ? 'tab active' : 'tab'} onClick={() => onChange(t.id)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function KeyValues({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v ?? '-'}</dd>
        </div>
      ))}
    </dl>
  );
}
