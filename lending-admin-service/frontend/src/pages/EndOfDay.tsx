import { FormEvent, useEffect, useState } from 'react';
import { send, get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, dateTime, label, Tone } from '../format';
import { usePolling } from '../hooks';
import type { EodJobRow, EodRunResult, EodStatus } from '../types';

const FAST_MS = 3000;   // while end of day is running
const SLOW_MS = 10000;  // otherwise

type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

function jobTone(status: string): Tone {
  switch (status) {
    case 'COMPLETED': return 'good';
    case 'SKIPPED': return 'neutral';
    case 'RUNNING': case 'STARTED': case 'STARTING': return 'info';
    case 'PENDING': return 'neutral';
    default: return 'bad'; // FAILED, STOPPED, UNKNOWN, ...
  }
}

function duration(from: string | null, to: string | null, now: number): string {
  if (!from) return '-';
  const end = to ? new Date(to).getTime() : now;
  const s = Math.max(0, Math.round((end - new Date(from).getTime()) / 1000));
  const m = Math.floor(s / 60);
  return m > 0 ? `${m} min ${String(s % 60).padStart(2, '0')} s` : `${s} s`;
}

export default function EndOfDay() {
  const { data, error, lastChecked, refresh } = usePolling(
    () => get<EodStatus>('/eod/status'),
    // Faster while running, and while the batch service has not yet got the business date from lending-service (waking up).
    d => (d?.running || d?.progress?.state === 'RUNNING' || (d?.reachable && !d.businessDate) || (d && !d.reachable) ? FAST_MS : SLOW_MS),
  );
  const [notice, setNotice] = useState<Notice>(null);
  const [confirming, setConfirming] = useState(false);
  const [now, setNow] = useState(Date.now());

  // A confirmation message is only useful for a few seconds; the progress card carries on from there.
  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 8000);
    return () => clearTimeout(t);
  }, [notice]);

  const progress = data?.progress;
  // The batch service clears its running flag a moment after the last job reports, so trust the progress state first.
  const running = progress?.state === 'RUNNING' || (!!data?.running && (!progress || progress.state === 'IDLE'));

  // One-second clock so the elapsed times move between polls.
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [running]);

  if (!data) {
    return (
      <>
        <PageHeader title="End of day" sub="Start, restart and follow the end-of-day batch" />
        {error ? <ErrorBox message={error} onRetry={refresh} /> : <Loading what="Contacting the admin service" />}
        {!error && <p className="muted">This can take up to a minute when the service has been idle (free-tier services sleep and need to wake up).</p>}
      </>
    );
  }

  const bd = data.businessDate ?? null;
  const businessDate = bd?.businessDate ?? data.databaseBusinessDate;
  const dateStatus = bd?.status ?? data.databaseStatus;
  const eodMarked = dateStatus === 'EOD_RUNNING';
  const interrupted = eodMarked && !running && (progress?.state === 'IDLE' || progress?.state === 'ADVANCED');
  const failed = progress?.state === 'FAILED';
  const canStart = data.configured && data.reachable && !running && !!bd && !!businessDate;
  const isRestart = eodMarked; // the date is already in EOD_RUNNING, so this resumes
  const percent = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

  const cols: Col<EodJobRow>[] = [
    { key: 'o', header: 'Order', align: 'right', render: r => (progress ? progress.jobs.indexOf(r) + 1 : '') },
    { key: 'j', header: 'Job', render: r => <strong className="mono">{r.jobName}</strong> },
    { key: 's', header: 'Status', render: r => <Badge tone={jobTone(r.status)}>{label(r.status)}</Badge> },
    { key: 'st', header: 'Started', render: r => (r.status === 'SKIPPED' ? '-' : dateTime(r.startedAt)) },
    { key: 'd', header: 'Took', align: 'right', render: r => (r.status === 'SKIPPED' ? '-' : duration(r.startedAt, r.finishedAt, now)) },
    { key: 'm', header: 'Message', render: r => r.message || '' },
  ];

  return (
    <>
      <PageHeader
        title="End of day"
        sub="Start, restart and follow the end-of-day batch. This screen checks the status automatically."
        actions={<span className="muted" role="status">
          {running ? 'Checking every 3 s' : 'Checking every 10 s'}
          {lastChecked ? ` | last checked ${lastChecked.toLocaleTimeString('en-GB')}` : ''}
          {' '}<button className="btn" onClick={refresh}>Check now</button>
        </span>}
      />

      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {!data.configured && <div className="banner banner-warn">{data.error}</div>}
      {data.configured && !data.reachable && (
        <div className="banner banner-warn" role="alert">
          The batch service could not be reached ({data.error}). On the free tier it can take a minute to wake up; this screen keeps trying.
          The date below is read from the shared database.
        </div>
      )}
      {data.configured && data.reachable && !bd && (
        <div className="banner banner-warn" role="status">
          The batch service is up but has not yet got the business date from lending-service (it may be waking up). Checking again every few
          seconds; the date below is read from the shared database, and Start unlocks once lending-service answers.
        </div>
      )}
      {error && <div className="banner banner-warn" role="alert">The last status check failed: {error}. Showing the last known status.</div>}
      {interrupted && (
        <div className="banner banner-warn">
          The business date is in end-of-day state but nothing is running (the batch service may have restarted). Restart end of day to
          resume: jobs that already completed are skipped.
        </div>
      )}
      {failed && (
        <div className="banner banner-bad" role="alert">
          End of day stopped: {progress?.message}
        </div>
      )}
      {progress?.state === 'ADVANCED' && (
        <div className="banner banner-good">
          {progress.message} Finished {dateTime(progress.finishedAt)}.
        </div>
      )}

      <div className="kpi-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 12, marginBottom: 14 }}>
        <Kpi title="Business date" value={date(businessDate)} sub={bd && !bd.workingDay ? 'Not a working day' : 'The day end of day will close'} />
        <Kpi
          title="Status"
          value={running ? 'Running' : eodMarked ? 'Stopped part-way' : 'Open'}
          tone={running ? undefined : eodMarked ? 'warn' : 'good'}
          sub={running ? (progress?.currentJob ? `Now: ${progress.currentJob}` : 'Starting') : eodMarked ? 'Restart to resume' : 'Ready to run'}
        />
        <Kpi title="Next business date" value={date(bd?.nextBusinessDate)} sub="After a successful run" />
        <Kpi title="Last closed date" value={date(bd?.previousBusinessDate)} sub={bd?.systemDate ? `Calendar date ${date(bd.systemDate)}` : undefined} />
      </div>

      <Card
        title="Run"
        actions={
          <button className="btn btn-primary" disabled={!canStart} onClick={() => setConfirming(true)}>
            {running ? 'End of day is running...' : isRestart ? `Restart end of day for ${date(businessDate)}` : `Start end of day for ${date(businessDate)}`}
          </button>
        }
      >
        <p className="muted" style={{ margin: 0 }}>
          {isRestart
            ? `The business date ${date(businessDate)} is already in end-of-day state. Restarting resumes it: jobs that completed are skipped, the one that failed is run again, then the date moves to ${date(bd?.nextBusinessDate)}.`
            : `Runs every enabled daily job for ${date(businessDate)} in order. When all of them complete the business date moves to ${date(bd?.nextBusinessDate)}. If a job fails the date stays at ${date(businessDate)} and you can restart from here.`}
        </p>
      </Card>

      <Card
        title={progress && progress.businessDate ? `Progress for ${date(progress.businessDate)}${progress.resumed ? ' (resumed)' : ''}` : 'Progress'}
        actions={progress && progress.total > 0 ? <span className="muted">{progress.done} of {progress.total} jobs done</span> : undefined}
      >
        {(!progress || progress.state === 'IDLE') && (
          <p className="muted" style={{ margin: 0 }}>No end of day has run since the batch service last started. Progress appears here as soon as one begins.</p>
        )}
        {progress && progress.state !== 'IDLE' && (
          <>
            <div style={{ display: 'flex', gap: 16, alignItems: 'center', marginBottom: 10 }}>
              <div className="progress" style={{ flex: 1 }} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-label="Jobs completed">
                <div style={{ width: `${percent}%`, background: failed ? 'var(--bad)' : undefined }} />
              </div>
              <strong>{percent}%</strong>
            </div>
            <p className="muted" style={{ marginTop: 0 }}>
              {progress.state === 'RUNNING' ? progress.message : progress.state === 'ADVANCED' ? 'Finished' : 'Stopped'}
              {progress.startedAt ? ` | started ${dateTime(progress.startedAt)}` : ''}
              {progress.startedAt ? ` | ${progress.state === 'RUNNING' ? 'elapsed' : 'took'} ${duration(progress.startedAt, progress.finishedAt, now)}` : ''}
              {progress.actor ? ` | by ${progress.actor}` : ''}
            </p>
            <DataTable
              columns={cols}
              rows={progress.jobs}
              rowKey={r => r.jobName}
              rowClass={r => (r.status === 'RUNNING' ? 'row-running' : undefined)}
              empty={progress.state === 'RUNNING' ? 'Waiting for the job list from lending-service...' : 'No jobs ran'}
            />
          </>
        )}
      </Card>

      {confirming && businessDate && (
        <ConfirmDialog
          businessDate={businessDate}
          nextDate={bd?.nextBusinessDate ?? null}
          restart={isRestart}
          onClose={() => setConfirming(false)}
          onResult={n => { setConfirming(false); setNotice(n); refresh(); }}
        />
      )}
    </>
  );
}

function ConfirmDialog({ businessDate, nextDate, restart, onClose, onResult }: {
  businessDate: string; nextDate: string | null; restart: boolean; onClose: () => void; onResult: (n: Notice) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, busy]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      const r = await send<EodRunResult>('POST', '/eod/run', { expectedBusinessDate: businessDate });
      onResult({ tone: 'good', text: r.started === false ? r.message : 'End of day started. Progress is shown below.' });
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label={restart ? 'Restart end of day' : 'Start end of day'} onSubmit={submit}>
        <h2>{restart ? 'Restart end of day?' : 'Start end of day?'}</h2>
        <p style={{ fontSize: 16 }}>Business date: <strong>{date(businessDate)}</strong></p>
        <p>
          {restart
            ? `This resumes end of day for ${date(businessDate)}. Jobs that already completed are skipped and the rest run. `
            : `This runs every enabled daily job for ${date(businessDate)}. `}
          When all jobs complete, {date(businessDate)} is closed and the business date becomes <strong>{date(nextDate)}</strong>. That cannot be
          undone.
        </p>
        {err && <div className="banner banner-bad" role="alert">{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? 'Starting...' : restart ? `Restart for ${date(businessDate)}` : `Start for ${date(businessDate)}`}
          </button>
        </div>
      </form>
    </div>
  );
}
