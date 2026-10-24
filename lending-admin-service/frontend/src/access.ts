import type { Me } from './types';

/**
 * Which logins may open which screens. Mirrors AccessPolicy.java on the server (the server is the one that enforces it; this only keeps
 * the menu and routes tidy). Everyone who signs in gets every inquiry; ADMIN can open everything.
 */
export type Role = 'ADMIN' | 'BATCH_OPS' | 'LOAN_OPS' | 'LIMITS_OPS';

/** Screens that need a role. A path not listed here is an inquiry (dashboard, accounts, upcoming, overdue, payments, accruals, ledger, chit groups). */
const AREAS: [string, Role[]][] = [
  ['/products', ['LOAN_OPS']],
  ['/ops', ['LOAN_OPS']],
  ['/limits', ['LIMITS_OPS']],
  // Chit: every inquiry is open; creating a group is ADMIN only for now (no chit role), and so is every change the server enforces.
  ['/chit/groups/new', []],
  ['/accounting', []],
  ['/batch', ['BATCH_OPS']],
  ['/eod', ['BATCH_OPS']],
  ['/config', ['BATCH_OPS']],
  ['/maintenance', []],
];

export function canOpen(me: Me | null, path: string): boolean {
  if (!me) return false;
  const roles = me.roles ?? ['ADMIN'];
  if (roles.includes('ADMIN')) return true;
  const area = AREAS.find(([p]) => path === p || path.startsWith(`${p}/`));
  if (!area) return true;
  return area[1].some(r => roles.includes(r));
}

const LABEL: Record<Role, string> = {
  ADMIN: 'Administrator', BATCH_OPS: 'Batch operations', LOAN_OPS: 'Loan operations', LIMITS_OPS: 'Limits operations',
};
export function roleLabel(me: Me | null): string {
  const roles = (me?.roles ?? []) as Role[];
  return roles.map(r => LABEL[r] ?? r).join(', ');
}
