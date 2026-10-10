import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader, Pager } from '../components/ui';
import { count, dateTime, label } from '../format';
import { useLoad } from '../hooks';
import type { OutboxResponse, OutboxRow } from '../types';

export default function Ledger() {
  const nav = useNavigate();
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useLoad(() => get<OutboxResponse>('/ledger-outbox', { status, page, size: 20 }), [status, page]);

  const cols: Col<OutboxRow>[] = [
    { key: 'id', header: 'Entry', render: r => r.outboxId },
    { key: 'a', header: 'Account', render: r => <strong>{r.accountNumber}</strong> },
    { key: 'e', header: 'Event', render: r => label(r.eventType) },
    { key: 's', header: 'Posting status', render: r => <Badge tone={r.postingStatus === 'POSTED' ? 'good' : r.postingStatus === 'FAILED' ? 'bad' : 'warn'}>{label(r.postingStatus)}</Badge> },
    { key: 'r', header: 'Retries', align: 'right', render: r => r.retryCount },
    { key: 'l', header: 'Last attempt', render: r => dateTime(r.lastAttemptedAt) },
    { key: 'c', header: 'Created', render: r => dateTime(r.createdAt) },
  ];

  const pick = (s: string) => { setStatus(status === s ? '' : s); setPage(0); };

  return (
    <>
      <PageHeader title="Ledger outbox" sub="Accounting events waiting to be posted to the core banking ledger" />
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <div className="kpis">
            {(['PENDING', 'POSTED', 'FAILED'] as const).map(s => (
              <button key={s} className={`kpi kpi-button ${status === s ? 'kpi-selected' : ''}`} onClick={() => pick(s)} aria-pressed={status === s}>
                <div className="kpi-title">{label(s)}</div>
                <div className="kpi-value">{count(data.counts[s] ?? 0)}</div>
                <div className="kpi-sub">{status === s ? 'Click to clear filter' : 'Click to filter'}</div>
              </button>
            ))}
            <Kpi title="Total entries" value={count(Object.values(data.counts).reduce((a, b) => a + b, 0))} />
          </div>
          <Card title="Outbox entries">
            <DataTable columns={cols} rows={data.page.items} rowKey={r => r.outboxId} onRowClick={r => nav(`/accounts/${r.accountId}`)} empty="No entries" />
            <Pager page={data.page} onPage={setPage} />
          </Card>
        </>
      )}
    </>
  );
}
