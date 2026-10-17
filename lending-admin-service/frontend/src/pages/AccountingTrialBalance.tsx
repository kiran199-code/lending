import { useState } from 'react';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Kpi, Loading, PageHeader } from '../components/ui';
import { date, downloadCsv, money } from '../format';
import { useLoad } from '../hooks';
import type { BranchInfo, LedgerInfo, TrialBalance, TrialBalanceRow } from '../types';

/** Debits, credits and net per general-ledger account, up to a date. */
export default function AccountingTrialBalance() {
  const [asOf, setAsOf] = useState('');
  const [ledgerId, setLedgerId] = useState('');
  const [branchCode, setBranchCode] = useState('');
  const branches = useLoad(() => get<BranchInfo[]>('/accounting/branches'), []);
  const ledgers = useLoad(() => get<LedgerInfo[]>('/accounting/ledgers'), []);
  const tb = useLoad(() => get<TrialBalance>('/accounting/trial-balance', { asOf, ledgerId, branchCode }), [asOf, ledgerId, branchCode]);

  const cols: Col<TrialBalanceRow>[] = [
    { key: 'l', header: 'Ledger', render: r => r.ledgerCode },
    { key: 'g', header: 'GL account', render: r => <span className="mono">{r.glAccountCode}</span> },
    { key: 'n', header: 'Name', render: r => r.accountName },
    { key: 'br', header: 'Branch', render: r => r.branchCode },
    { key: 'c', header: 'Currency', render: r => r.currency },
    { key: 'd', header: 'Debits', align: 'right', render: r => money(r.debits) },
    { key: 'cr', header: 'Credits', align: 'right', render: r => money(r.credits) },
    { key: 'nd', header: 'Net (debit +)', align: 'right', render: r => <strong>{money(r.netDebit)}</strong> },
  ];

  function exportCsv() {
    if (!tb.data) return;
    downloadCsv(`trial-balance-${tb.data.asOf}.csv`, ['Ledger', 'GL account', 'Name', 'Branch', 'Currency', 'Debits', 'Credits', 'Net debit'],
      tb.data.rows.map(r => [r.ledgerCode, r.glAccountCode, r.accountName, r.branchCode, r.currency, r.debits, r.credits, r.netDebit]));
  }

  const d = tb.data;
  return (
    <>
      <PageHeader title="Trial balance" sub="What the general ledger holds in each account. Shadow journals are not counted." />
      <Card actions={<button className="btn" onClick={exportCsv} disabled={!d}>Export (CSV)</button>}>
        <div className="filters">
          <label className="inline-label">As of <input type="date" value={asOf} onChange={e => setAsOf(e.target.value)} /></label>
          <select value={ledgerId} onChange={e => setLedgerId(e.target.value)} aria-label="Ledger">
            <option value="">All ledgers</option>
            {(ledgers.data ?? []).map(l => <option key={l.ledgerId} value={l.ledgerId}>{l.ledgerCode} ({l.baseCurrency})</option>)}
          </select>
          <select value={branchCode} onChange={e => setBranchCode(e.target.value)} aria-label="Branch">
            <option value="">All branches</option>
            {(branches.data ?? []).map(b => <option key={b.branchCode} value={b.branchCode}>{b.branchCode} - {b.branchName}</option>)}
          </select>
          <span className="muted">Leave the date empty for the current business date.</span>
        </div>
        {tb.loading && !d && <Loading />}
        {tb.error && <ErrorBox message={tb.error} onRetry={tb.reload} />}
        {d && (
          <>
            <div className="kpis">
              <Kpi title="As of" value={date(d.asOf)} />
              <Kpi title="Total debits" value={money(d.totalDebits)} />
              <Kpi title="Total credits" value={money(d.totalCredits)} />
              <Kpi title="Balanced" value={<Badge tone={d.balanced ? 'good' : 'bad'}>{d.balanced ? 'Yes' : 'No'}</Badge>}
                sub={d.balanced ? undefined : `Difference ${money(d.totalDebits - d.totalCredits)}`} tone={d.balanced ? 'good' : 'bad'} />
            </div>
            <DataTable columns={cols} rows={d.rows} rowKey={r => `${r.ledgerId}-${r.glAccountCode}-${r.currency}-${r.branchCode}`} empty="No journals up to this date" />
          </>
        )}
      </Card>
    </>
  );
}
