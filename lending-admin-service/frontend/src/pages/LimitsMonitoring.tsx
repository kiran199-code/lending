import { useState } from 'react';
import { Link } from 'react-router-dom';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { ActingAsBar, LimitsGate, NoticeBanner, StateBadge, useAction } from '../components/limits';
import { Badge, Card, ErrorBox, Loading, PageHeader, Tabs } from '../components/ui';
import { date, dateTime, label } from '../format';
import { useLoad } from '../hooks';
import type { Breach, Integrity, SweepResult } from '../types';

type Status = 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED' | 'ALL';

export default function LimitsMonitoring() {
  return (
    <>
      <PageHeader title="Limit monitoring" sub="Breaches of limits and collateral, the sweep that finds them, and the counter check" />
      <LimitsGate><Body /></LimitsGate>
    </>
  );
}

function Body() {
  const [status, setStatus] = useState<Status>('OPEN');
  const list = useLoad(() => get<Breach[]>('/limits/breaches', { status: status === 'ALL' ? '' : status }), [status]);
  const act = useAction();
  const [sweep, setSweep] = useState<SweepResult | null>(null);
  const [integ, setInteg] = useState<Integrity | null>(null);

  const cols: Col<Breach>[] = [
    { key: 'id', header: '#', render: r => r.id },
    { key: 't', header: 'Breach', render: r => <>{label(r.breachType)}<div className="muted">{r.detail}</div></> },
    { key: 'sev', header: 'Severity', render: r => <Badge tone={r.severity === 'HIGH' || r.severity === 'CRITICAL' ? 'bad' : r.severity === 'MEDIUM' ? 'warn' : 'neutral'}>{label(r.severity)}</Badge> },
    { key: 'f', header: 'Facility / collateral', render: r => (
      <>
        {r.facilityRef && <div><Link to={`/limits/facilities/${encodeURIComponent(r.facilityRef)}`}>{r.facilityRef}</Link></div>}
        {r.collateralRef && <div><Link to={`/limits/collateral/${encodeURIComponent(r.collateralRef)}`}>{r.collateralRef}</Link></div>}
      </>
    ) },
    { key: 's', header: 'Status', render: r => <><StateBadge status={r.status} />{r.acknowledgedBy && <div className="muted">by {r.acknowledgedBy}</div>}</> },
    { key: 'd', header: 'Detected', render: r => dateTime(r.detectedAt) },
    { key: 'c', header: 'Closed', render: r => dateTime(r.closedAt) },
    { key: 'x', header: '', render: r => (r.status === 'OPEN'
      ? <button className="btn" disabled={act.busy} onClick={() => act.run(() => send('POST', `/limits/breaches/${r.id}/acknowledge`), () => `Breach ${r.id} acknowledged.`, list.reload)}>Acknowledge</button>
      : null) },
  ];

  return (
    <>
      <ActingAsBar needed={false} />
      <NoticeBanner notice={act.notice} />
      <div className="grid-2">
        <Card title="Run the sweep now">
          <p className="muted">LCM runs this every minute on its own. It releases expired reservations, expires facilities past their date, and opens or closes breaches.</p>
          <button className="btn btn-primary" disabled={act.busy}
            onClick={() => act.run(() => send<SweepResult>('POST', '/limits/monitoring/sweep'), r => `Sweep for ${date(r.asOf)} done.`, r => { setSweep(r); list.reload(); })}>Run sweep</button>
          {sweep && (
            <p style={{ marginBottom: 0 }}>
              As of {date(sweep.asOf)}: {sweep.reservationsExpired} reservation(s) expired, {sweep.facilitiesExpired} facility(ies) expired,
              {' '}{sweep.breachesOpened} breach(es) opened, {sweep.breachesClosed} closed.
            </p>
          )}
        </Card>
        <Card title="Counter check">
          <p className="muted">Confirms that every facility's used amount equals the sum of its ledger movements. Any difference means something changed a counter outside the service.</p>
          <button className="btn" disabled={act.busy}
            onClick={() => act.run(() => get<Integrity>('/limits/monitoring/integrity'), r => (r.ok ? 'All facility counters agree with the ledger.' : `${r.differences.length} difference(s) found.`), setInteg)}>Check now</button>
          {integ && !integ.ok && <ul>{integ.differences.map(d => <li key={d}>{d}</li>)}</ul>}
        </Card>
      </div>
      <Card title="Breaches">
        <Tabs<Status> tabs={[{ id: 'OPEN', label: 'Open' }, { id: 'ACKNOWLEDGED', label: 'Acknowledged' }, { id: 'CLOSED', label: 'Closed' }, { id: 'ALL', label: 'All' }]} value={status} onChange={setStatus} />
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && <DataTable columns={cols} rows={list.data} rowKey={r => r.id} empty={status === 'OPEN' ? 'No open breaches' : 'Nothing to show'} />}
      </Card>
    </>
  );
}
