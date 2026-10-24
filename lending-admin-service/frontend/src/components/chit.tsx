import { ReactNode } from 'react';
import { get } from '../api';
import { canOpen } from '../access';
import { useAuth } from '../auth';
import { label, Tone } from '../format';
import { useLoad } from '../hooks';
import { Badge, ErrorBox, Loading } from './ui';

export function groupTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': return 'good';
    case 'DRAFT': case 'OPEN_FOR_ENROLMENT': return 'info';
    case 'FULL': case 'CLOSING': return 'warn';
    case 'CANCELLED': return 'bad';
    default: return 'neutral'; // CLOSED
  }
}

/** One tone mapping for every status word the chit screens show (groups, tickets, fees, security, cycles). */
export function chitTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE': case 'RECEIVED': case 'VERIFIED': case 'HELD': case 'COMPLETED': return 'good';
    case 'DRAFT': case 'OPEN_FOR_ENROLMENT': case 'ENROLLED': case 'PENDING': case 'SCHEDULED': return 'info';
    case 'FULL': case 'CLOSING': case 'PRIZED': case 'REFUNDED': case 'RELEASED': return 'warn';
    case 'CANCELLED': case 'DEFAULTED': case 'FAILED': return 'bad';
    default: return 'neutral'; // CLOSED, EXITED, TRANSFERRED
  }
}

export function GroupBadge({ status }: { status: string }) {
  return <Badge tone={groupTone(status)}>{label(status)}</Badge>;
}

export function ChitBadge({ status }: { status: string }) {
  return <Badge tone={chitTone(status)}>{label(status)}</Badge>;
}

/** Whether this login may change chit data (the server enforces it; this only hides buttons that would be refused). */
export function useCanChangeChit(): boolean {
  const { me } = useAuth();
  return canOpen(me, '/chit/groups/new');
}

export function ChitGate({ children }: { children: ReactNode }) {
  const st = useLoad(() => get<{ enabled: boolean }>('/chit/status'), []);
  if (st.loading && !st.data) return <Loading what="Checking chit service" />;
  if (st.error) return <ErrorBox message={st.error} onRetry={st.reload} />;
  if (st.data && !st.data.enabled) {
    return (
      <div className="banner banner-warn" role="alert">
        The chit screens are not set up. Set CHIT_SERVICE_URL (the public address of lending-chit-service) and CHIT_SERVICE_PASSWORD
        (the password lending-chit-service uses for CHIT_SERVICE_PASSWORD) on the admin service and restart it.
      </div>
    );
  }
  return <>{children}</>;
}

/** Whole-number text field check used by the chit forms. */
export const wholeNumber = (v: string) => /^\d+$/.test(v.trim());
