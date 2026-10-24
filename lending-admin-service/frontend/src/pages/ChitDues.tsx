import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { get, send } from '../api';
import { ChitBadge, ChitGate, useCanChangeChit } from '../components/chit';
import { RecordPaymentDialog, ReceiptModal, overdueText } from '../components/chitCollection';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, NoticeBanner, useActingAs } from '../components/limits';
import { ConfirmModal, Notice } from '../components/ops';
import { Card, ErrorBox, Kpi, Loading, PageHeader, Tabs } from '../components/ui';
import { count, date, label, money } from '../format';
import { useLoad } from '../hooks';
import type { ChitDue, ChitDuesPage, ChitPayment, ChitRunSummary } from '../types';

type View = 'OVERDUE' | 'UPCOMING' | 'OPEN';
const VIEWS: { id: View; label: string }[] = [
  { id: 'OVERDUE', label: 'Overdue' }, { id: 'UPCOMING', label: 'Upcoming' }, { id: 'OPEN', label: 'All open' },
];
const HELP: Record<View, string> = {
  OVERDUE: 'Instalments past their due date that still have something unpaid, oldest first.',
  UPCOMING: 'Instalments that fall due soon. They are projected from the cycle calendar, so they only become demands when the daily demand job runs.',
  OPEN: 'Every raised demand with something still unpaid, overdue or not.',
};
const PAGE = 50;

const JOBS = [
  { key: 'demand-run', title: 'Raise demands', does: 'Raises the instalment for every cycle that has fallen due, for every ticket. Uses up any advance a ticket holds.' },
  { key: 'late-fee-run', title: 'Charge late fees', does: 'Charges one day of late fee on every unpaid demand past its grace period. A day is never charged twice.' },
  { key: 'default-run', title: 'Check defaults', does: 'Flags tickets with too many unpaid instalments as defaulted, and clears the flag where everything overdue is paid.' },
] as const;

export default function ChitDues() {
  return (
    <>
      <PageHeader title="Chit dues and payments" sub="What is owed across all groups, and where to record the money received" />
      <ChitGate><Body /></ChitGate>
    </>
  );
}

function Body() {
  const canChange = useCanChangeChit();
  const actor = useActingAs();
  const [sp, setSp] = useSearchParams();
  const view = (['OVERDUE', 'UPCOMING', 'OPEN'].includes(sp.get('view') ?? '') ? sp.get('view') : 'OVERDUE') as View;
  const group = (sp.get('group') ?? '').trim();
  const within = Number(sp.get('within') ?? '14') || 14;
  const offset = Number(sp.get('offset') ?? '0') || 0;
  const [groupText, setGroupText] = useState(group);
  const [tick, setTick] = useState(0);
  const [notice, setNotice] = useState<Notice>(null);
  const [pay, setPay] = useState<ChitDue | null>(null);
  const [receipt, setReceipt] = useState<ChitPayment | null>(null);
  const [job, setJob] = useState<(typeof JOBS)[number] | null>(null);

  const { data, error, loading, reload } = useLoad(
    () => get<ChitDuesPage>('/chit/dues', {
      view, groupCode: group || undefined, withinDays: view === 'UPCOMING' ? within : undefined, limit: PAGE, offset,
    }), [view, group, within, offset, tick]);

  function update(next: Record<string, string>) {
    const n = new URLSearchParams(sp);
    Object.entries(next).forEach(([k, v]) => (v === '' ? n.delete(k) : n.set(k, v)));
    setSp(n);
  }

  const rows = data?.rows ?? [];
  const totalSub = rows.reduce((a, r) => a + r.subscriptionOutstanding, 0);
  const totalFee = rows.reduce((a, r) => a + r.lateFeeOutstanding, 0);
  const tickets = new Set(rows.map(r => `${r.groupCode}#${r.ticketNo}`)).size;

  const cols: Col<ChitDue>[] = [
    { key: 'g', header: 'Group', render: r => <Link to={`/chit/groups/${encodeURIComponent(r.groupCode)}`}>{r.groupCode}</Link> },
    { key: 't', header: 'Ticket', render: r => <Link to={`/chit/groups/${encodeURIComponent(r.groupCode)}/tickets/${r.ticketNo}`}><strong>#{r.ticketNo}</strong></Link> },
    { key: 'c', header: 'Customer', render: r => r.customerId },
    { key: 'cy', header: 'Cycle', align: 'right', render: r => r.cycleNo },
    { key: 'due', header: 'Due date', render: r => date(r.dueDate) },
    { key: 'od', header: 'Overdue', render: r => (r.demandRef ? overdueText(r.daysOverdue) : <span className="muted">not raised yet</span>) },
    { key: 'sub', header: 'Instalment', align: 'right', render: r => money(r.subscriptionOutstanding) },
    { key: 'fee', header: 'Late fee', align: 'right', render: r => (r.lateFeeOutstanding > 0 ? money(r.lateFeeOutstanding) : '-') },
    { key: 'tot', header: 'Total', align: 'right', render: r => <strong>{money(r.totalOutstanding)}</strong> },
    { key: 'st', header: 'Ticket', render: r => <ChitBadge status={r.ticketStatus} /> },
    { key: 'act', header: '', render: r => (canChange ? <button className="btn btn-primary" onClick={() => setPay(r)}>Record payment</button> : null) },
  ];

  async function runJob(j: (typeof JOBS)[number]) {
    const r = await send<ChitRunSummary>('POST', `/chit/batch/${j.key}`);
    const figs = Object.entries(r.figures ?? {}).map(([k, v]) => `${label(k.replace(/([A-Z])/g, ' $1'))}: ${/total|used/i.test(k) ? money(v) : count(v)}`).join('; ');
    const failed = r.groupsFailed > 0;
    const text = `${j.title} for ${date(r.businessDate)}: ${r.groupsProcessed} group(s) processed${figs ? `; ${figs}` : ''}.`
      + (failed ? ` ${r.groupsFailed} group(s) failed: ${r.failures.map(f => `${f.groupCode} (${f.message})`).join(', ')}.` : '');
    setNotice({ tone: failed ? 'warn' : 'good', text });
    setJob(null);
    setTick(t => t + 1);
  }

  return (
    <>
      <ActingAsBar needed={false} />
      <NoticeBanner notice={notice} />

      {canChange && (
        <Card title="Daily runs">
          <p className="muted" style={{ marginTop: 0 }}>
            The batch service runs these every day once enabled. Run them here to test or to catch up. Each is safe to repeat:
            a second run on the same day changes nothing.
          </p>
          <div className="filters">
            {JOBS.map(j => <button key={j.key} className="btn" onClick={() => setJob(j)}>{j.title}</button>)}
          </div>
        </Card>
      )}

      <Card title="Dues">
        <Tabs tabs={VIEWS} value={view} onChange={v => update({ view: v, offset: '' })} />
        <p className="muted">{HELP[view]}</p>
        <form className="filters" onSubmit={e => { e.preventDefault(); update({ group: groupText.trim(), offset: '' }); }}>
          <input value={groupText} onChange={e => setGroupText(e.target.value)} placeholder="Group code (all groups if empty)" aria-label="Group code" />
          {view === 'UPCOMING' && (
            <select value={within} onChange={e => update({ within: e.target.value, offset: '' })} aria-label="Falling due within">
              {[7, 14, 30, 60, 90].map(d => <option key={d} value={d}>Within {d} days</option>)}
            </select>
          )}
          <button type="submit" className="btn">Apply</button>
          <button type="button" className="btn" onClick={reload}>Refresh</button>
        </form>

        {loading && !data && <Loading what="Loading dues (the chit service may be waking up)" />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && (
          <>
            <div className="kpis">
              <Kpi title="Instalments shown" value={count(rows.length)} sub={`${tickets} ticket${tickets === 1 ? '' : 's'} · as of ${date(data.asOf)}`} />
              <Kpi title="Instalment outstanding" value={money(totalSub)} />
              <Kpi title="Late fees outstanding" value={money(totalFee)} tone={totalFee > 0 ? 'warn' : undefined} />
              <Kpi title="Total" value={money(totalSub + totalFee)} tone={view === 'OVERDUE' && totalSub + totalFee > 0 ? 'bad' : undefined} />
            </div>
            <DataTable columns={cols} rows={rows} rowKey={r => `${r.groupCode}|${r.ticketNo}|${r.cycleNo}`}
              empty={view === 'OVERDUE' ? 'Nothing is overdue.' : view === 'UPCOMING' ? 'Nothing falls due in this period.' : 'Nothing is outstanding.'} />
            {(offset > 0 || rows.length === PAGE) && (
              <div className="pager">
                <span className="muted">Rows {offset + 1} to {offset + rows.length}</span>
                <button className="btn" disabled={offset === 0} onClick={() => update({ offset: String(Math.max(0, offset - PAGE)) })}>Previous</button>
                <button className="btn" disabled={rows.length < PAGE} onClick={() => update({ offset: String(offset + PAGE) })}>Next</button>
              </div>
            )}
            <p className="muted">Totals cover the rows shown. Open a ticket to see its full account and payments.</p>
          </>
        )}
      </Card>

      {pay && (
        <RecordPaymentDialog code={pay.groupCode} ticketNo={pay.ticketNo} customerId={pay.customerId} suggested={pay.totalOutstanding} actor={actor}
          onDone={p => {
            setPay(null);
            setReceipt(p);
            setNotice({ tone: 'good', text: `Payment ${p.paymentRef} of ${money(p.amount)} recorded for ${p.groupCode} #${p.ticketNo}: ${money(p.allocated)} applied to dues`
              + `${p.advance > 0 ? `, ${money(p.advance)} held as advance` : ''}${p.replayed ? ' (the same request had already been applied; nothing new was done)' : ''}.` });
            setTick(t => t + 1);
          }}
          onClose={() => setPay(null)} />
      )}
      {receipt && <ReceiptModal code={receipt.groupCode} paymentRef={receipt.paymentRef} onClose={() => setReceipt(null)} />}

      {job && (
        <ConfirmModal title={`${job.title} now?`} confirmLabel="Run now"
          rows={[['Run', job.title], ['Run as', actor]]}
          note={`${job.does} The run uses the platform business date, not today's calendar date, and is safe to repeat.`}
          onConfirm={() => runJob(job)} onClose={() => setJob(null)} />
      )}
    </>
  );
}
