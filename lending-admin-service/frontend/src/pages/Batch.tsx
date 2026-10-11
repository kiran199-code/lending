import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, Empty, ErrorBox, Loading, PageHeader } from '../components/ui';
import { dateTime, label } from '../format';
import { useLoad } from '../hooks';
import type { BatchExecution, BatchJob, BatchOverview } from '../types';

export default function Batch() {
  const { data, error, loading, reload } = useLoad(() => get<BatchOverview>('/batch'), []);

  const jobCols: Col<BatchJob>[] = [
    { key: 'p', header: 'Order', align: 'right', render: r => r.priority ?? '-' },
    { key: 'n', header: 'Job', render: r => <strong className="mono">{r.jobName}</strong> },
    { key: 'c', header: 'Category', render: r => label(r.category) },
    { key: 't', header: 'Type', render: r => label(r.type) },
    { key: 'cr', header: 'Schedule (cron)', render: r => <span className="mono">{r.cron}</span> },
    { key: 'e', header: 'Enabled', render: r => <Badge tone={r.enabled ? 'good' : 'neutral'}>{r.enabled ? 'Enabled' : 'Disabled'}</Badge> },
    { key: 'to', header: 'Timeout', align: 'right', render: r => (r.timeoutMinutes ? `${r.timeoutMinutes} min` : '-') },
  ];
  const exCols: Col<BatchExecution>[] = [
    { key: 'id', header: 'Execution', render: r => r.executionId },
    { key: 'n', header: 'Job', render: r => <span className="mono">{r.jobName}</span> },
    { key: 's', header: 'Status', render: r => <Badge tone={r.status === 'COMPLETED' ? 'good' : r.status === 'FAILED' ? 'bad' : 'warn'}>{label(r.status)}</Badge> },
    { key: 'x', header: 'Exit code', render: r => r.exitCode ?? '-' },
    { key: 'st', header: 'Started', render: r => dateTime(r.startTime) },
    { key: 'en', header: 'Ended', render: r => dateTime(r.endTime) },
  ];

  return (
    <>
      <PageHeader title="Batch jobs" sub="Job list and recent runs; edit job settings under Configuration" />
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          {data.note && <Empty>{data.note}</Empty>}
          {data.jobs.length > 0 && (
            <Card title={`Configured jobs (${data.jobs.length})`}>
              <DataTable columns={jobCols} rows={data.jobs} rowKey={r => r.jobName} />
            </Card>
          )}
          {data.jobs.length > 0 && (
            <Card title="Recent executions">
              <DataTable columns={exCols} rows={data.recentExecutions} rowKey={r => r.executionId} empty="No job has run yet" />
            </Card>
          )}
        </>
      )}
    </>
  );
}
