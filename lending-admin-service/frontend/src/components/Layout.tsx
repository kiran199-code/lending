import { NavLink, Outlet } from 'react-router-dom';
import { get } from '../api';
import { useAuth } from '../auth';
import { date } from '../format';
import { useLoad } from '../hooks';

interface BusinessDateView { businessDate: string; status: string | null; fromLending: boolean }

const NAV = [
  ['/', 'Dashboard'],
  ['/products', 'Products'],
  ['/accounts', 'Accounts'],
  ['/upcoming', 'Upcoming payments'],
  ['/overdue', 'Overdue'],
  ['/payments', 'Payments'],
  ['/accruals', 'Interest accruals'],
  ['/ledger', 'Ledger outbox'],
  ['/batch', 'Batch jobs'],
  ['/config', 'Configuration'],
  ['/maintenance', 'Maintenance'],
] as const;

export default function Layout() {
  const { me, logout } = useAuth();
  const bd = useLoad(() => get<BusinessDateView>('/business-date'), []);
  const b = bd.data;
  return (
    <div className="shell">
      <aside className="side">
        <div className="brand">Lending Admin</div>
        <nav>
          {NAV.map(([to, text]) => (
            <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'nav active' : 'nav')}>
              {text}
            </NavLink>
          ))}
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
