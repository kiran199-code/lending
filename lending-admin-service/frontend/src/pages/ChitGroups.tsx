import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { get } from '../api';
import { ChitGate, GroupBadge, useCanChangeChit } from '../components/chit';
import DataTable, { Col } from '../components/DataTable';
import { Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { ChitGroup, ChitGroupPage } from '../types';

const STATUSES = ['DRAFT', 'OPEN_FOR_ENROLMENT', 'FULL', 'ACTIVE', 'CLOSING', 'CLOSED', 'CANCELLED'];
const PAGE = 25;

export default function ChitGroups() {
  const canChange = useCanChangeChit();
  return (
    <>
      <PageHeader title="Chit groups" sub="Create a group, enrol its tickets and activate it"
        actions={canChange ? <Link className="btn btn-primary" to="/chit/groups/new">New group</Link> : undefined} />
      <ChitGate><List /></ChitGate>
    </>
  );
}

function List() {
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const status = sp.get('status') ?? '';
  const offset = Number(sp.get('offset') ?? '0') || 0;
  const { data, error, loading, reload } = useLoad(
    () => get<ChitGroupPage>('/chit/groups', { status, limit: PAGE, offset }), [status, offset]);

  function update(next: Record<string, string>) {
    const n = new URLSearchParams(sp);
    Object.entries(next).forEach(([k, v]) => (v === '' ? n.delete(k) : n.set(k, v)));
    setSp(n);
  }

  const cols: Col<ChitGroup>[] = [
    { key: 'code', header: 'Group', render: r => <strong>{r.groupCode}</strong> },
    { key: 'name', header: 'Name', render: r => r.name },
    { key: 'status', header: 'Status', render: r => <GroupBadge status={r.status} /> },
    { key: 'value', header: 'Chit value', align: 'right', render: r => money(r.chitValue) },
    { key: 'sub', header: 'Subscription', align: 'right', render: r => money(r.subscription) },
    { key: 'seats', header: 'Tickets', align: 'right', render: r => `${r.ticketsEnrolled} of ${r.ticketCount}` },
    { key: 'freq', header: 'Frequency', render: r => label(r.frequency) },
    { key: 'start', header: 'Starts', render: r => date(r.startDate) },
    { key: 'foreman', header: 'Foreman', render: r => r.foremanRef },
  ];

  return (
    <Card>
      <div className="filters">
        <select value={status} onChange={e => update({ status: e.target.value, offset: '' })} aria-label="Status">
          <option value="">All statuses</option>
          {STATUSES.map(s => <option key={s} value={s}>{label(s)}</option>)}
        </select>
        <button type="button" className="btn" onClick={reload}>Refresh</button>
      </div>
      {loading && !data && <Loading what="Loading groups (the chit service may be waking up)" />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <DataTable columns={cols} rows={data.items} rowKey={r => r.groupCode} onRowClick={r => nav(`/chit/groups/${encodeURIComponent(r.groupCode)}`)}
            empty="No groups yet. Create one with New group." />
          <div className="pager">
            <span className="muted">{data.total} {data.total === 1 ? 'group' : 'groups'}</span>
            {data.total > PAGE && (
              <>
                <button className="btn" disabled={offset === 0} onClick={() => update({ offset: String(Math.max(0, offset - PAGE)) })}>Previous</button>
                <button className="btn" disabled={offset + PAGE >= data.total} onClick={() => update({ offset: String(offset + PAGE) })}>Next</button>
              </>
            )}
          </div>
        </>
      )}
    </Card>
  );
}
