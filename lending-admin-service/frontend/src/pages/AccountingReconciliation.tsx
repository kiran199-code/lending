import { useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, dateTime, label, money } from '../format';
import { useLoad } from '../hooks';
import type { Reconciliation, ReconciliationCheck } from '../types';

const NAMES: Record<string, string> = {
  LEDGER_BALANCED: 'Every ledger balances',
  NO_OPEN_GATE_EXCEPTIONS: 'No exception is holding up end of day',
  RECON_PRINCIPAL_NOT_DUE: 'Principal not yet due',
  RECON_PRINCIPAL_DUE: 'Principal due',
  RECON_INTEREST_ACCRUED: 'Interest accrued, not yet due',
  RECON_INTEREST_DUE: 'Interest due',
  RECON_PENAL: 'Penal interest',
  RECON_ADVANCE: 'Advance held',
  RECON_PRIMARY_LEDGER: 'Primary ledger',
  RECON_ACCOUNT_DIMENSIONS: 'Journals carry the account\'s currency and branch',
  ACCOUNTING_DISABLED: 'Accounting switched off',
};

/**
 * Proves the general ledger agrees with the loan sub-ledger, account by account. The end-of-day job runs this every night;
 * running it here uses the same rules and stores the result. It can only be run for the current business date.
 */
export default function AccountingReconciliation() {
  const { data, error, loading, reload } = useLoad(() => get<Reconciliation>('/accounting/reconciliation'), []);
  const [busy, setBusy] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setRunError(null);
    try {
      await send<Reconciliation>('POST', '/accounting/reconciliation/run');
      reload();
    } catch (e) {
      setRunError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const cols: Col<ReconciliationCheck>[] = [
    { key: 'c', header: 'Check', render: r => <strong>{NAMES[r.checkCode] ?? label(r.checkCode)}</strong> },
    { key: 's', header: 'Result', render: r => <Badge tone={r.status === 'PASS' ? 'good' : 'bad'}>{r.status === 'PASS' ? 'Pass' : 'Fail'}</Badge> },
    { key: 'e', header: 'Loan sub-ledger', align: 'right', render: r => money(r.expected) },
    { key: 'a', header: 'General ledger', align: 'right', render: r => money(r.actual) },
    { key: 'd', header: 'Difference', align: 'right', render: r => <strong>{money(r.difference)}</strong> },
    { key: 'x', header: 'Detail', render: r => r.detail ?? '' },
    { key: 'w', header: 'Run', render: r => dateTime(r.runAt) },
  ];

  return (
    <>
      <PageHeader title="Reconciliation" sub="The general ledger against the loan tables, account by account"
        actions={<button className="btn btn-primary" onClick={run} disabled={busy}>{busy ? 'Running...' : 'Run now'}</button>} />
      {runError && <div className="banner banner-bad" role="alert">{runError}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <div className="kpis">
            <Kpi title="Business date" value={date(data.businessDate)} />
            <Kpi title="Result" value={data.checks.length === 0 ? 'Not run' : data.allPassed ? 'All checks pass' : 'Differences found'}
              tone={data.checks.length === 0 ? 'neutral' : data.allPassed ? 'good' : 'bad'}
              sub={data.checks.length === 0 ? 'No run is stored for this date yet' : `${data.checks.filter(c => c.status !== 'PASS').length} failing of ${data.checks.length}`} />
          </div>
          <Card title="Checks (latest run)">
            <DataTable columns={cols} rows={data.checks} rowKey={r => r.checkCode} empty="Nothing has been reconciled for this date yet. Use Run now." />
          </Card>
        </>
      )}
    </>
  );
}
