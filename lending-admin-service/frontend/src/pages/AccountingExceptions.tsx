import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader, Tabs } from '../components/ui';
import { count, dateTime, label } from '../format';
import { useLoad } from '../hooks';
import type { ExceptionGate, PostOutcome, PostingException } from '../types';

type Status = 'OPEN' | 'RESOLVED' | 'WAIVED' | 'ALL';
type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

/**
 * Events that could not be journalled. A BLOCKING event that fails rolls the business transaction back; a non-blocking one
 * (interest and penal accrual) is queued here so the loan keeps moving, and those that gate end of day hold the night until
 * they are retried or waived.
 */
export default function AccountingExceptions() {
  const nav = useNavigate();
  const [status, setStatus] = useState<Status>('OPEN');
  const [notice, setNotice] = useState<Notice>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [waiving, setWaiving] = useState<number | null>(null);
  const [reason, setReason] = useState('');

  const gate = useLoad(() => get<ExceptionGate>('/accounting/exceptions/gate'), []);
  const list = useLoad(() => get<PostingException[]>('/accounting/exceptions', { status, limit: 200 }), [status]);

  function refresh() { gate.reload(); list.reload(); }

  async function retry(id: number) {
    setBusyId(id);
    setNotice(null);
    try {
      const r = await send<PostOutcome>('POST', `/accounting/exceptions/${id}/retry`);
      setNotice({ tone: 'good', text: `Exception ${id}: ${label(r.status)}${r.journalNo ? ` - journal ${r.journalNo}` : ''}${r.note ? ` (${r.note})` : ''}` });
      refresh();
    } catch (e) {
      setNotice({ tone: 'bad', text: `Exception ${id} could not be retried: ${e instanceof Error ? e.message : String(e)}` });
      refresh();
    } finally {
      setBusyId(null);
    }
  }

  async function waive(id: number) {
    setBusyId(id);
    setNotice(null);
    try {
      await send('POST', `/accounting/exceptions/${id}/waive`, { reason });
      setNotice({ tone: 'warn', text: `Exception ${id} waived. Nothing was journalled for it.` });
      setWaiving(null);
      setReason('');
      refresh();
    } catch (e) {
      setNotice({ tone: 'bad', text: `Exception ${id} could not be waived: ${e instanceof Error ? e.message : String(e)}` });
    } finally {
      setBusyId(null);
    }
  }

  const cols: Col<PostingException>[] = [
    { key: 'id', header: '#', render: r => r.exceptionId },
    { key: 'e', header: 'Event', render: r => label(r.eventCode) },
    { key: 'a', header: 'Account', render: r => (r.accountId === null ? '-' : (
      <button className="link-button" onClick={() => nav(`/accounts/${r.accountId}`)}>{r.accountId}</button>
    )) },
    { key: 'r', header: 'Why', render: r => (
      <>
        <strong>{label(r.reason)}</strong>
        {r.errorMessage && <div className="muted">{r.errorMessage}</div>}
      </>
    ) },
    { key: 'g', header: 'Holds end of day', align: 'center', render: r => (r.eodGate && r.status === 'OPEN' ? <Badge tone="bad">Yes</Badge> : <Badge tone="neutral">No</Badge>) },
    { key: 'at', header: 'Attempts', align: 'right', render: r => r.attempts },
    { key: 'c', header: 'First failed', render: r => dateTime(r.createdAt) },
    { key: 'l', header: 'Last attempt', render: r => dateTime(r.lastAttemptAt) },
    { key: 's', header: 'Status', render: r => (
      <>
        <Badge tone={r.status === 'OPEN' ? 'bad' : r.status === 'RESOLVED' ? 'good' : 'neutral'}>{label(r.status)}</Badge>
        {r.resolvedBy && <div className="muted">{r.resolvedBy}{r.resolvedNote ? `: ${r.resolvedNote}` : ''}</div>}
      </>
    ) },
    { key: 'x', header: '', render: r => {
      if (r.status !== 'OPEN') return null;
      if (waiving === r.exceptionId) {
        return (
          <div className="filters">
            <input placeholder="Reason (required)" value={reason} onChange={e => setReason(e.target.value)} aria-label="Reason for waiving" />
            <button className="btn btn-primary" disabled={busyId !== null || reason.trim() === ''} onClick={() => waive(r.exceptionId)}>Waive</button>
            <button className="btn" onClick={() => { setWaiving(null); setReason(''); }}>Cancel</button>
          </div>
        );
      }
      return (
        <div className="filters">
          <button className="btn btn-primary" disabled={busyId !== null} onClick={() => retry(r.exceptionId)}>{busyId === r.exceptionId ? 'Retrying...' : 'Retry'}</button>
          <button className="btn" disabled={busyId !== null} onClick={() => { setWaiving(r.exceptionId); setReason(''); }}>Waive</button>
        </div>
      );
    } },
  ];

  const g = gate.data;
  return (
    <>
      <PageHeader title="Posting exceptions" sub="Loan events that could not be journalled and are waiting for a retry or a decision" />
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {g && g.openGateExceptions > 0 && (
        <div className="banner banner-bad" role="alert">
          {count(g.openGateExceptions)} open exception{g.openGateExceptions === 1 ? '' : 's'} hold up end of day. Fix the cause (usually a missing route or
          template), then retry; or waive with a reason if the event must not be journalled.
        </div>
      )}
      <div className="kpis">
        <Kpi title="Open exceptions" value={g ? count(g.openExceptions) : '-'} tone={g && g.openExceptions > 0 ? 'warn' : 'good'} />
        <Kpi title="Holding up end of day" value={g ? count(g.openGateExceptions) : '-'} tone={g && g.openGateExceptions > 0 ? 'bad' : 'good'} />
      </div>
      <Card>
        <Tabs<Status> tabs={[{ id: 'OPEN', label: 'Open' }, { id: 'RESOLVED', label: 'Resolved' }, { id: 'WAIVED', label: 'Waived' }, { id: 'ALL', label: 'All' }]}
          value={status} onChange={s => { setStatus(s); setWaiving(null); }} />
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.exceptionId} empty={status === 'OPEN' ? 'No open exceptions' : 'Nothing to show'} />}
      </Card>
    </>
  );
}
