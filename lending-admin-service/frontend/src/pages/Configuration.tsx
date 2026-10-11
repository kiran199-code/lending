import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader, Tabs } from '../components/ui';
import { dateTime, label } from '../format';
import { useLoad } from '../hooks';
import type {
  ApplyResult, BatchConfigResponse, BatchJobConfig, BatchJobUpdate, ConfigOverview, FixedRule, ServiceSettings, SettingRow,
} from '../types';

type Tab = 'jobs' | 'settings' | 'rules';
type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

export default function Configuration() {
  const [tab, setTab] = useState<Tab>('jobs');
  return (
    <>
      <PageHeader
        title="Configuration"
        sub="Batch job settings can be edited here. Everything else is shown for reference and is changed in the environment of each service."
      />
      <Tabs
        tabs={[
          { id: 'jobs', label: 'Batch jobs (editable)' },
          { id: 'settings', label: 'Service settings' },
          { id: 'rules', label: 'Fixed business rules' },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'jobs' && <BatchJobs />}
      {tab === 'settings' && <ServiceSettingsView />}
      {tab === 'rules' && <Rules />}
    </>
  );
}

// ------------------------------------------------------------------ batch jobs

function BatchJobs() {
  const { data, error, loading, reload } = useLoad(() => get<BatchConfigResponse>('/config/batch-jobs'), []);
  const [q, setQ] = useState('');
  const [cat, setCat] = useState('');
  const [enabled, setEnabled] = useState('');
  const [editing, setEditing] = useState<BatchJobConfig | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [applying, setApplying] = useState(false);
  const [pendingApply, setPendingApply] = useState(false);

  const rows = useMemo(() => {
    const t = q.trim().toLowerCase();
    return (data?.jobs ?? []).filter(j =>
      (!t || j.jobName.toLowerCase().includes(t)) &&
      (!cat || j.category === cat) &&
      (!enabled || String(j.enabled) === enabled));
  }, [data, q, cat, enabled]);

  async function applyNow() {
    setApplying(true);
    try {
      const r = await send<{ message: string }>('POST', '/config/batch-jobs/apply');
      setNotice({ tone: 'good', text: r.message });
      setPendingApply(false);
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setApplying(false);
    }
  }

  function saved(r: ApplyResult) {
    setEditing(null);
    setNotice({ tone: r.applied ? 'good' : 'warn', text: `${r.job.jobName}: ${r.message}` });
    setPendingApply(!r.applied);
    reload();
  }

  const cols: Col<BatchJobConfig>[] = [
    { key: 'p', header: 'Order', align: 'right', render: r => r.priority },
    { key: 'n', header: 'Job', render: r => <strong className="mono">{r.jobName}</strong> },
    { key: 'c', header: 'Category / type', render: r => `${label(r.category)} / ${label(r.type)}` },
    { key: 'cr', header: 'Schedule (cron)', render: r => <span className="mono">{r.cron ?? 'on demand'}</span> },
    { key: 'e', header: 'Enabled', render: r => <Badge tone={r.enabled ? 'good' : 'neutral'}>{r.enabled ? 'Enabled' : 'Disabled'}</Badge> },
    { key: 'ch', header: 'Chunk', align: 'right', render: r => r.chunkSize ?? '-' },
    { key: 'co', header: 'Threads', align: 'right', render: r => r.concurrency ?? '-' },
    { key: 'rt', header: 'Retry', align: 'right', render: r => r.retryLimit },
    { key: 'sk', header: 'Skip', align: 'right', render: r => r.skipLimit },
    { key: 'to', header: 'Timeout (min)', align: 'right', render: r => r.timeoutMinutes ?? '-' },
    {
      key: 'ed', header: '', align: 'right',
      render: r => <button className="btn" disabled={!data?.editable} onClick={() => setEditing(r)} aria-label={`Edit ${r.jobName}`}>Edit</button>,
    },
  ];

  return (
    <>
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {data?.note && <div className="banner banner-warn">{data.note}</div>}
      {data && data.jobs.length > 0 && (
        <Card
          title={`Batch jobs (${rows.length} of ${data.jobs.length})`}
          actions={
            <>
              {pendingApply && <Badge tone="warn">Saved changes not yet applied</Badge>}
              <button className="btn btn-primary" disabled={!data.editable || applying} onClick={applyNow}>
                {applying ? 'Applying...' : 'Apply changes'}
              </button>
            </>
          }
        >
          <p className="muted">
            Saving writes the settings through lending-batch-service, which validates them. The running scheduler only
            picks them up after <em>Apply changes</em> (a reload; no restart). Disabling a job stops its schedule.
          </p>
          <div className="filters">
            <input className="grow" placeholder="Filter by job name..." value={q} onChange={e => setQ(e.target.value)} aria-label="Filter jobs" />
            <select value={cat} onChange={e => setCat(e.target.value)} aria-label="Category">
              <option value="">All categories</option>
              {['DAILY', 'MONTHLY', 'PERIODIC', 'EVENT_TRIGGERED'].map(c => <option key={c} value={c}>{label(c)}</option>)}
            </select>
            <select value={enabled} onChange={e => setEnabled(e.target.value)} aria-label="Enabled">
              <option value="">Enabled and disabled</option>
              <option value="true">Enabled</option>
              <option value="false">Disabled</option>
            </select>
          </div>
          <DataTable columns={cols} rows={rows} rowKey={r => r.configId} empty="No job matches" />
        </Card>
      )}
      {editing && <EditJob job={editing} onClose={() => setEditing(null)} onSaved={saved} />}
    </>
  );
}

const num = (s: string): number | null => (s.trim() === '' ? null : Number(s));

function EditJob({ job, onClose, onSaved }: { job: BatchJobConfig; onClose: () => void; onSaved: (r: ApplyResult) => void }) {
  const chunk = job.type === 'CHUNK';
  const [f, setF] = useState({
    enabled: job.enabled,
    cron: job.cron ?? '',
    restartable: job.restartable,
    chunkSize: job.chunkSize?.toString() ?? '',
    concurrency: job.concurrency?.toString() ?? '',
    throttleLimit: job.throttleLimit?.toString() ?? '',
    retryLimit: String(job.retryLimit),
    skipLimit: String(job.skipLimit),
    priority: String(job.priority),
    timeoutMinutes: job.timeoutMinutes?.toString() ?? '',
  });
  const [busy, setBusy] = useState<'save' | 'apply' | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => {
    first.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const set = (k: keyof typeof f, v: string | boolean) => setF(p => ({ ...p, [k]: v }));

  /** Only the fields that differ from what was loaded are sent. */
  function diff(): BatchJobUpdate {
    const u: BatchJobUpdate = { expectedVersion: job.version };
    if (f.enabled !== job.enabled) u.enabled = f.enabled;
    if (f.cron.trim() !== (job.cron ?? '')) u.cronExpression = f.cron.trim();
    if (f.restartable !== job.restartable) u.restartable = f.restartable;
    const nums: [keyof BatchJobUpdate, string, number | null][] = [
      ['chunkSize', f.chunkSize, job.chunkSize], ['concurrency', f.concurrency, job.concurrency],
      ['throttleLimit', f.throttleLimit, job.throttleLimit], ['retryLimit', f.retryLimit, job.retryLimit],
      ['skipLimit', f.skipLimit, job.skipLimit], ['priority', f.priority, job.priority],
      ['timeoutMinutes', f.timeoutMinutes, job.timeoutMinutes],
    ];
    for (const [key, text, old] of nums) {
      const v = num(text);
      if (v !== null && v !== old) (u as Record<string, unknown>)[key] = v;
    }
    return u;
  }
  const changes = Object.keys(diff()).filter(k => k !== 'expectedVersion');

  async function submit(apply: boolean, e?: FormEvent) {
    e?.preventDefault();
    if (changes.length === 0) { onClose(); return; }
    setBusy(apply ? 'apply' : 'save');
    setErr(null);
    try {
      onSaved(await send<ApplyResult>('PUT', `/config/batch-jobs/${encodeURIComponent(job.jobName)}`, diff(), { apply }));
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(null);
    }
  }

  const field = (k: keyof typeof f, text: string, o: { disabled?: boolean; hint?: string; type?: string; wide?: boolean; min?: number } = {}) => (
    <label className={`field ${o.wide ? 'wide' : ''}`}>
      <span>{text}</span>
      <input
        ref={k === 'cron' ? first : undefined}
        type={o.type ?? 'text'} min={o.min} disabled={o.disabled}
        value={String(f[k])} onChange={e => set(k, e.target.value)}
        className={o.type === 'text' || !o.type ? 'mono' : undefined}
      />
      {o.hint && <span className="hint">{o.hint}</span>}
    </label>
  );

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label={`Edit ${job.jobName}`} onSubmit={e => submit(true, e)}>
        <h2 className="mono">{job.jobName}</h2>
        <p className="muted">
          {label(job.category)} job, {label(job.type)} type. Version {job.version}, last changed {dateTime(job.updatedAt)}.
        </p>
        <div className="form-grid">
          <label className="field check">
            <input type="checkbox" checked={f.enabled} onChange={e => set('enabled', e.target.checked)} />
            <span>Enabled (scheduled to run)</span>
          </label>
          <label className="field check">
            <input type="checkbox" checked={f.restartable} onChange={e => set('restartable', e.target.checked)} />
            <span>Restartable after failure</span>
          </label>
          {field('cron', 'Schedule (cron, 6 fields)', {
            wide: true, disabled: job.category === 'EVENT_TRIGGERED',
            hint: job.category === 'EVENT_TRIGGERED' ? 'Event-triggered jobs have no schedule.' : 'Example: 0 0 1 * * ?  = every day at 01:00 (service time).',
          })}
          {field('chunkSize', 'Chunk size', { type: 'number', min: 1, disabled: !chunk, hint: chunk ? 'Records per commit.' : 'Only for chunk jobs.' })}
          {field('concurrency', 'Concurrency (partitions)', { type: 'number', min: 1, disabled: !chunk })}
          {field('throttleLimit', 'Thread limit', { type: 'number', min: 1, disabled: !chunk })}
          {field('retryLimit', 'Retry limit', { type: 'number', min: 0 })}
          {field('skipLimit', 'Skip limit', { type: 'number', min: 0 })}
          {field('priority', 'Order (lower runs first)', { type: 'number' })}
          {field('timeoutMinutes', 'Timeout (minutes)', { type: 'number', min: 1 })}
        </div>
        {err && <div className="banner banner-bad" role="alert">{err}</div>}
        <div className="modal-foot">
          <span className="muted" style={{ marginRight: 'auto' }}>
            {changes.length === 0 ? 'No changes yet' : `${changes.length} change${changes.length > 1 ? 's' : ''}`}
          </span>
          <button type="button" className="btn" onClick={onClose} disabled={busy !== null}>Cancel</button>
          <button type="button" className="btn" disabled={busy !== null || changes.length === 0} onClick={() => submit(false)}>
            {busy === 'save' ? 'Saving...' : 'Save only'}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy !== null || changes.length === 0}>
            {busy === 'apply' ? 'Saving and applying...' : 'Save and apply'}
          </button>
        </div>
      </form>
    </div>
  );
}

// ------------------------------------------------------------------ service settings

function ServiceSettingsView() {
  const { data, error, loading, reload } = useLoad(() => get<ConfigOverview>('/config'), []);
  const [svc, setSvc] = useState<string>('lending-admin-service');
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  const current: ServiceSettings = data.services.find(s => s.service === svc) ?? data.services[0];

  const cols: Col<SettingRow>[] = [
    {
      key: 'n', header: 'Setting',
      render: r => (<div style={{ minWidth: 240 }}><strong>{r.name}</strong><div className="setting-desc">{r.description}</div></div>),
    },
    { key: 'e', header: 'Variable', render: r => <span className="mono">{r.envVar}</span> },
    ...(current.liveValues
      ? [{ key: 'v', header: 'Current value', render: (r: SettingRow) => <span className={`mono ${r.secret ? 'secret' : ''}`}>{r.value}</span> } as Col<SettingRow>]
      : []),
    { key: 'd', header: 'Default', render: r => <span className="mono">{r.defaultValue}</span> },
    { key: 's', header: 'Render sandbox', render: r => <span className="mono">{r.sandboxValue}</span> },
    { key: 'r', header: 'Change takes effect', render: r => (r.restartRequired ? 'After restart' : 'Immediately') },
  ];

  return (
    <>
      <Tabs
        tabs={data.services.map(s => ({ id: s.service, label: s.service }))}
        value={svc}
        onChange={setSvc}
      />
      <div className="banner">
        {current.description}
        <br />
        <strong>Where to change:</strong> {current.changeVia}.
        {!current.liveValues && ' Secrets are never shown.'}
      </div>
      {current.groups.map(g => (
        <Card key={g.title} title={g.title}>
          <DataTable columns={cols} rows={g.settings} rowKey={r => r.envVar + r.name} />
        </Card>
      ))}
    </>
  );
}

// ------------------------------------------------------------------ fixed rules

function Rules() {
  const { data, error, loading, reload } = useLoad(() => get<ConfigOverview>('/config'), []);
  if (loading && !data) return <Loading />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  const cols: Col<FixedRule>[] = [
    { key: 'a', header: 'Area', render: r => <strong>{r.area}</strong> },
    { key: 'r', header: 'Rule', render: r => r.rule },
    { key: 'v', header: 'How it works', render: r => r.value },
  ];
  return (
    <Card title="Behaviour fixed in the code (not configurable)">
      <p className="muted">These rules are part of the services themselves. Changing one needs a code change and a release, so there is no setting for it.</p>
      <DataTable columns={cols} rows={data.fixedRules} rowKey={r => r.area + r.rule} />
    </Card>
  );
}
