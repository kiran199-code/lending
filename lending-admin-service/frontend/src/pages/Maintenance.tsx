import { FormEvent, useEffect, useRef, useState } from 'react';
import { get, send } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { count } from '../format';
import { useLoad } from '../hooks';
import type { BaseProductsResult, ClearDataResult, MaintenanceStatus } from '../types';

const TABLE_NAMES: Record<string, string> = {
  loan_account: 'Loan accounts',
  amortization_schedule: 'Schedule instalments',
  interest_accrual: 'Interest accruals',
  emi_transaction: 'EMI and other payments',
  mortgage: 'Mortgages',
  mortgage_valuation_history: 'Mortgage valuations',
  ledger_outbox: 'Ledger outbox entries',
  processed_request: 'Idempotency log',
  loan_product: 'Loan products',
  acct_journal: 'Accounting journals',
  acct_journal_line: 'Accounting journal lines',
  acct_idempotency: 'Accounting idempotency keys',
  acct_journal_reversal: 'Accounting reversal links',
  acct_accrual_carry: 'Accounting rounding carries',
  acct_posting_exception: 'Accounting posting exceptions',
  acct_balance_daily: 'Accounting daily balances',
  acct_reconciliation: 'Accounting reconciliation results',
};

const LCM_TABLE_NAMES: Record<string, string> = {
  facility: 'Facilities',
  facility_change: 'Facility changes',
  facility_excess: 'Temporary excess',
  utilisation_position: 'Utilisation positions',
  reservation: 'Reservations',
  limit_txn: 'Limit ledger entries',
  idempotency_key: 'LCM idempotency keys',
  lcm_event: 'LCM events',
  audit_log: 'LCM audit log',
  collateral: 'Collateral',
  collateral_valuation: 'Collateral valuations',
  collateral_allocation: 'Collateral allocations',
  breach: 'Breaches',
};

const BATCH_TABLE_NAMES: Record<string, string> = {
  batch_job_instance: 'Job instances',
  batch_job_execution: 'Job executions',
  batch_job_execution_params: 'Job parameters',
  batch_job_execution_context: 'Job execution contexts',
  batch_step_execution: 'Step executions',
  batch_step_execution_context: 'Step execution contexts',
  batch_job_audit_log: 'Step audit log',
  batch_job_error_records: 'Skipped and failed items',
};

type Notice = { tone: 'good' | 'warn' | 'bad'; text: string } | null;

export default function Maintenance() {
  const { data, error, loading, reload } = useLoad(() => get<MaintenanceStatus>('/maintenance/status'), []);
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);

  async function createBase() {
    setBusy(true);
    setNotice(null);
    try {
      const r = await send<BaseProductsResult>('POST', '/maintenance/base-products');
      setNotice({ tone: 'good', text: r.message });
      reload();
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  const missing = data ? data.baseProducts.filter(p => !p.exists).length : 0;
  const eodRunning = data?.businessDateStatus === 'EOD_RUNNING';
  const lcm = data?.lcm;
  const lcmBlocks = !!lcm?.configured && !(lcm.reachable && lcm.resetEnabled);
  const batch = data?.batch;
  const batchBlocks = !!batch?.configured && !(batch.reachable && batch.resetEnabled && !batch.busy);
  const canClear = !!data?.resetEnabled && !eodRunning && !lcmBlocks && !batchBlocks;
  const lendingRows = data ? Object.values(data.rowCounts).reduce((a, b) => a + b, 0) : 0;
  const lcmRows = lcm?.rowCounts ? Object.values(lcm.rowCounts).reduce((a, b) => a + b, 0) : 0;
  const batchRows = batch?.rowCounts ? Object.values(batch.rowCounts).reduce((a, b) => a + b, 0) : 0;
  const totalRows = lendingRows + lcmRows + batchRows;

  const countCols: Col<[string, number]>[] = [
    { key: 't', header: 'Data', render: r => TABLE_NAMES[r[0]] ?? r[0] },
    { key: 'n', header: 'Rows', align: 'right', render: r => count(r[1]) },
  ];
  const lcmCountCols: Col<[string, number]>[] = [
    { key: 't', header: 'Limits and collateral', render: r => LCM_TABLE_NAMES[r[0]] ?? r[0] },
    { key: 'n', header: 'Rows', align: 'right', render: r => count(r[1]) },
  ];

  const batchCountCols: Col<[string, number]>[] = [
    { key: 't', header: 'Batch job history', render: r => BATCH_TABLE_NAMES[r[0]] ?? r[0] },
    { key: 'n', header: 'Rows', align: 'right', render: r => count(r[1]) },
  ];

  return (
    <>
      <PageHeader title="Maintenance" sub="Start again from scratch in a test environment" />
      <div className="banner banner-warn">
        These actions change data, so they are done by lending-service, lending-lcm-service and lending-batch-service on this console's
        behalf; the console itself still never writes to the database. Use them in sandbox and test environments only.
      </div>
      {notice && <div className={`banner banner-${notice.tone}`} role="status">{notice.text}</div>}
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <Card
            title="Base product configuration"
            actions={<button className="btn btn-primary" disabled={busy || missing === 0} onClick={createBase}>
              {missing === 0 ? 'All base products exist' : `Create ${missing} missing`}
            </button>}
          >
            <p className="muted">
              The eleven standard and sample products: personal, business, education, gold, auto and home loans, plus the equal-principal, interest-only, balloon, step-up and study-moratorium samples that the end-to-end tests use. A product that already exists is
              left exactly as it is, so this is safe to run at any time. Floating products (auto, home) use the REPO benchmark; clearing
              keeps benchmark rates, so publish a REPO rate if you have not yet.
            </p>
            <div className="chips">
              {data.baseProducts.map(p => (
                <span key={p.productCode} style={{ marginRight: 12 }}>
                  <strong className="mono">{p.productCode}</strong> {p.productName}{' '}
                  <Badge tone={p.exists ? 'good' : 'neutral'}>{p.exists ? 'Exists' : 'Missing'}</Badge>
                </span>
              ))}
            </div>
          </Card>

          <section className="card danger-zone">
            <header className="card-head">
              <h3>Clear all data</h3>
              <div className="card-actions">
                <button className="btn btn-danger" disabled={!canClear || busy} onClick={() => setConfirming(true)}>Clear all data...</button>
              </div>
            </header>
            {!data.resetEnabled && (
              <div className="banner banner-warn">
                Clearing is switched off on lending-service. Set LENDING_RESET_ENABLED=true on it (Render: Environment tab) and redeploy to
                allow it.
              </div>
            )}
            {data.resetEnabled && lcm?.configured && !lcm.reachable && (
              <div className="banner banner-warn">
                lending-lcm-service could not be reached{lcm.error ? `: ${lcm.error}` : ''}. Open its address once to wake it, then reload this
                page. Nothing is cleared while it cannot be checked, so loans are never cleared without their facilities and collateral.
              </div>
            )}
            {data.resetEnabled && lcm?.configured && lcm.reachable && !lcm.resetEnabled && (
              <div className="banner banner-warn">
                Clearing is switched off on lending-lcm-service. Set LCM_RESET_ENABLED=true on it (Render: Environment tab) and redeploy to
                allow it.
              </div>
            )}
            {data.resetEnabled && batch?.configured && !batch.reachable && (
              <div className="banner banner-warn">
                lending-batch-service could not be reached{batch.error ? `: ${batch.error}` : ''}. Open its address once to wake it, then reload
                this page. Nothing is cleared while it cannot be checked, so loans are never cleared while the old job history is left behind.
              </div>
            )}
            {data.resetEnabled && batch?.configured && batch.reachable && !batch.resetEnabled && (
              <div className="banner banner-warn">
                Clearing is switched off on lending-batch-service. Set BATCH_RESET_ENABLED=true on it (Render: Environment tab) and redeploy to
                allow it.
              </div>
            )}
            {batch?.configured && batch.reachable && batch.resetEnabled && batch.busy && (
              <div className="banner banner-warn">{batch.busyReason ?? 'The batch service is busy'}. Wait for it to finish before clearing.</div>
            )}
            {batch && !batch.configured && (
              <div className="banner banner-warn">
                This console is not connected to lending-batch-service (BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD), so its job history will not
                be cleared with the loans, and end of day can then refuse dates that already ran.
              </div>
            )}
            {lcm && !lcm.configured && (
              <div className="banner banner-warn">
                This console is not connected to lending-lcm-service (LCM_SERVICE_URL and LCM_SERVICE_PASSWORD), so facilities and
                collateral will not be cleared with the loans.
              </div>
            )}
            {eodRunning && <div className="banner banner-warn">End of day is running. Wait for it to finish before clearing.</div>}
            <p className="muted">
              Removes every loan account with its schedule, accruals, payments, ledger outbox entries, idempotency log and accounting
              journals, every loan product, and, in lending-lcm-service, every facility, utilisation entry, collateral, breach, event and
              audit row, and, in lending-batch-service, the job execution history. Then it sets the business date to the current date (India
              time; it is {data.businessDate} now) and opens it. It cannot be undone. It keeps holidays, benchmark rates, the accounting and
              collateral-type configuration, and the batch service's job settings and schedules. Afterwards create the base products to
              start again.
            </p>
            <DataTable
              columns={countCols}
              rows={Object.entries(data.rowCounts)}
              rowKey={r => r[0]}
              footer={<tr><td className="muted">Total</td><td className="num muted" style={{ textAlign: 'right' }}>{count(lendingRows)}</td></tr>}
            />
            {lcm?.configured && lcm.reachable && lcm.rowCounts && (
              <DataTable
                columns={lcmCountCols}
                rows={Object.entries(lcm.rowCounts)}
                rowKey={r => r[0]}
                footer={<tr><td className="muted">Total</td><td className="num muted" style={{ textAlign: 'right' }}>{count(lcmRows)}</td></tr>}
              />
            )}
            {batch?.configured && batch.reachable && batch.rowCounts && (
              <DataTable
                columns={batchCountCols}
                rows={Object.entries(batch.rowCounts)}
                rowKey={r => r[0]}
                footer={<tr><td className="muted">Total</td><td className="num muted" style={{ textAlign: 'right' }}>{count(batchRows)}</td></tr>}
              />
            )}
          </section>
        </>
      )}
      {confirming && (
        <ClearDialog
          rows={totalRows}
          onClose={() => setConfirming(false)}
          onDone={text => { setConfirming(false); setNotice({ tone: 'good', text }); reload(); }}
        />
      )}
    </>
  );
}

function ClearDialog({ rows, onClose, onDone }: { rows: number; onClose: () => void; onDone: (text: string) => void }) {
  const [word, setWord] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    input.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (word !== 'RESET') return;
    setBusy(true);
    setErr(null);
    try {
      const r = await send<ClearDataResult>('POST', '/maintenance/clear-data', { confirm: 'RESET' });
      onDone(`${r.message} ${count(r.totalRows + (r.lcm?.totalRows ?? 0) + (r.batch?.totalRows ?? 0))} rows removed.`);
    } catch (ex) {
      setErr(ex instanceof Error ? ex.message : String(ex));
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <form className="modal" role="dialog" aria-modal="true" aria-label="Clear all data" onSubmit={submit}>
        <h2>Clear all data?</h2>
        <p>
          This permanently deletes {count(rows)} rows: all loan accounts, their schedules, accruals and payments, all loan products, all
          facilities and collateral, and the batch job history. The business date is then set to the current date. There is no undo.
        </p>
        <label className="field">
          <span>Type RESET to confirm</span>
          <input ref={input} value={word} onChange={e => setWord(e.target.value)} autoComplete="off" spellCheck={false} />
        </label>
        {err && <div className="banner banner-bad" role="alert" style={{ marginTop: 12 }}>{err}</div>}
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-danger" disabled={word !== 'RESET' || busy}>{busy ? 'Clearing...' : 'Clear all data'}</button>
        </div>
      </form>
    </div>
  );
}
