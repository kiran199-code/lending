import { useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { get } from '../api';
import { useAuth } from '../auth';
import { date } from '../format';
import { useLoad } from '../hooks';

interface BusinessDateView { businessDate: string; status: string | null; fromLending: boolean }

/** Menu sections. A section with no title (Overview) is always shown; the others fold. */
const SECTIONS: { title: string | null; items: [string, string][] }[] = [
  { title: null, items: [['/', 'Dashboard']] },
  { title: 'Product', items: [['/products', 'Products']] },
  {
    title: 'Account',
    items: [
      ['/ops/create', 'Create loan'], ['/ops/disburse', 'Disburse loan'], ['/ops/instalment', 'Pay instalment'],
      ['/ops/part-payment', 'Part-payment'], ['/ops/foreclosure', 'Foreclosure'], ['/ops/bulk', 'Bulk create / disburse'],
    ],
  },
  {
    title: 'Limits & collateral',
    items: [
      ['/limits/facilities', 'Facilities'], ['/limits/facilities/new', 'New facility'], ['/limits/approvals', 'Approvals'],
      ['/limits/collateral', 'Collateral'], ['/limits/collateral/new', 'New collateral'], ['/limits/monitoring', 'Monitoring'],
    ],
  },
  {
    title: 'Inquiry',
    items: [
      ['/accounts', 'Accounts'], ['/upcoming', 'Upcoming payments'], ['/overdue', 'Overdue'], ['/payments', 'Payments'],
      ['/accruals', 'Interest accruals'], ['/ledger', 'Ledger outbox'],
    ],
  },
  {
    title: 'Accounting',
    items: [
      ['/accounting/journals', 'Journals'], ['/accounting/trial-balance', 'Trial balance'],
      ['/accounting/exceptions', 'Posting exceptions'], ['/accounting/reconciliation', 'Reconciliation'],
      ['/accounting/currencies', 'Currencies'], ['/accounting/branches', 'Branches'],
    ],
  },
  { title: 'Batch', items: [['/batch', 'Batch jobs'], ['/eod', 'End of day']] },
  { title: 'Administration', items: [['/config', 'Configuration'], ['/maintenance', 'Maintenance']] },
];

const FOLD_KEY = 'lending-admin-menu-closed';
function readClosed(): string[] {
  try { return JSON.parse(sessionStorage.getItem(FOLD_KEY) ?? '[]') as string[]; } catch { return []; }
}
function writeClosed(v: string[]) {
  try { sessionStorage.setItem(FOLD_KEY, JSON.stringify(v)); } catch { /* storage blocked */ }
}

/** Whether a menu path is the page being shown (the account inquiry detail belongs to Accounts). */
function inSection(items: [string, string][], path: string): boolean {
  return items.some(([to]) => (to === '/' ? path === '/' : path === to || path.startsWith(`${to}/`)));
}


export default function Layout() {
  const { me, logout } = useAuth();
  const bd = useLoad(() => get<BusinessDateView>('/business-date'), []);
  const b = bd.data;
  const { pathname } = useLocation();
  const [closed, setClosed] = useState<string[]>(readClosed);
  function toggle(title: string) {
    const next = closed.includes(title) ? closed.filter(t => t !== title) : [...closed, title];
    setClosed(next);
    writeClosed(next);
  }
  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">Lending Admin</div>
        <nav aria-label="Main menu">
          {SECTIONS.map(s => {
            const links = (
              s.items.map(([to, text]) => (
                <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive && !(pathname !== to && s.items.some(([o]) => o === pathname)) ? 'nav active' : 'nav')}>
                  {text}
                </NavLink>
              ))
            );
            if (!s.title) return <div key="overview" className="nav-group">{links}</div>;
            // The section holding the current page stays open so the active entry is never hidden.
            const open = !closed.includes(s.title) || inSection(s.items, pathname);
            return (
              <div key={s.title} className="nav-group">
                <button type="button" className="nav-head" aria-expanded={open} onClick={() => toggle(s.title!)}>
                  <span>{s.title}</span><span aria-hidden="true" className="nav-caret">{open ? '\u25BE' : '\u25B8'}</span>
                </button>
                {open && <div className="nav-items">{links}</div>}
              </div>
            );
          })}
        </nav>
        {b && (
          <div className="side-foot" title={b.fromLending ? 'Set by lending-service' : 'lending-service has not created the business date yet; showing the wall-clock date'}>
            <div className="muted">Business date</div>
            <strong>{date(b.businessDate)}</strong>
            <div className="muted">
              {!b.fromLending ? 'wall clock (fallback)' : b.status === 'EOD_RUNNING' ? 'end of day running' : 'open'}
            </div>
          </div>
        )}
        <div className="side-foot">
          <div className="muted">Signed in as</div>
          <strong>{me?.username}</strong>
          <button className="btn btn-ghost" onClick={logout}>Sign out</button>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
