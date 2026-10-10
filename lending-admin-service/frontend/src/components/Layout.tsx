import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth';

const NAV = [
  ['/', 'Dashboard'],
  ['/accounts', 'Accounts'],
  ['/upcoming', 'Upcoming payments'],
  ['/overdue', 'Overdue'],
  ['/payments', 'Payments'],
  ['/accruals', 'Interest accruals'],
  ['/ledger', 'Ledger outbox'],
  ['/batch', 'Batch jobs'],
] as const;

export default function Layout() {
  const { me, logout } = useAuth();
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
