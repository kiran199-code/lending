import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, KeyValues, Loading, PageHeader } from '../components/ui';
import { date, dateTime, downloadCsv, label, money } from '../format';
import { useLoad } from '../hooks';
import type { BranchInfo, JournalDetail, JournalLine, JournalSummary } from '../types';

const PAGE = 50;

/** Journal explorer: every journal the accounting module has written, newest first, with its debit and credit lines. */
export default function AccountingJournals() {
  const nav = useNavigate();
  const [f, setF] = useState({ accountId: '', eventCode: '', journalNo: '', from: '', to: '', branchCode: '' });
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const set = (k: keyof typeof f, v: string) => { setF({ ...f, [k]: v }); setPage(0); setOpenId(null); };

  const branches = useLoad(() => get<BranchInfo[]>('/accounting/branches'), []);
  const list = useLoad(() => get<JournalSummary[]>('/accounting/journals', { ...f, limit: PAGE, offset: page * PAGE }), [f, page]);
  const detail = useLoad(() => (openId === null ? Promise.resolve(null) : get<JournalDetail>(`/accounting/journals/${openId}`)), [openId]);

  const cols: Col<JournalSummary>[] = [
    { key: 'n', header: 'Journal', render: r => <strong className="mono">{r.journalNo}</strong> },
    { key: 'b', header: 'Business date', render: r => date(r.businessDate) },
    { key: 'v', header: 'Value date', render: r => date(r.valueDate) },
    { key: 'e', header: 'Event', render: r => label(r.eventCode) },
    { key: 'a', header: 'Account', render: r => (r.accountId === null ? '-' : r.accountId) },
    { key: 'br', header: 'Branch', render: r => r.branchCode },
    { key: 'm', header: 'Mode', render: r => <Badge tone={r.postingMode === 'LIVE' ? 'good' : 'warn'}>{label(r.postingMode)}</Badge> },
    {
      key: 'f', header: 'Flags', render: r => (
        <>
          {r.reversalOfJournalId !== null && <Badge tone="info">Reversal</Badge>}{' '}
          {r.backdated && <Badge tone="warn">Backdated</Badge>}{' '}
          {r.priorPeriod && <Badge tone="bad">Prior period</Badge>}
        </>
      ),
    },
    { key: 'd', header: 'Narrative', render: r => r.narrative ?? '' },
  ];

  const lineCols: Col<JournalLine>[] = [
    { key: 's', header: '#', align: 'center', render: l => l.lineSeq },
    { key: 'l', header: 'Ledger', render: l => l.ledgerCode },
    { key: 'g', header: 'GL account', render: l => <span className="mono">{l.glAccountCode}</span> },
    { key: 'r', header: 'Role', render: l => label(l.roleCode) },
    { key: 'dr', header: 'Debit', align: 'right', render: l => (l.side === 'DR' ? money(l.amountBase) : '') },
    { key: 'cr', header: 'Credit', align: 'right', render: l => (l.side === 'CR' ? money(l.amountBase) : '') },
    { key: 'c', header: 'Currency', render: l => l.currencyBase + (l.currencyTxn !== l.currencyBase ? ` (from ${l.currencyTxn} at ${l.fxRate ?? '?'})` : '') },
  ];

  function exportCsv() {
    if (!list.data) return;
    downloadCsv('journals.csv', ['Journal', 'Business date', 'Value date', 'Event', 'Account', 'Branch', 'Currency', 'Mode', 'Source', 'Source id', 'Narrative'],
      list.data.map(r => [r.journalNo, r.businessDate, r.valueDate, r.eventCode, r.accountId, r.branchCode, r.txnCurrency, r.postingMode, r.sourceType, r.sourceId, r.narrative]));
  }

  const rows = list.data ?? [];
  const d = detail.data;
  return (
    <>
      <PageHeader title="Journals" sub="Every journal written for a loan event, newest first. Journals are never changed; a correction is a new, reversing journal." />
      <Card actions={<button className="btn" onClick={exportCsv} disabled={rows.length === 0}>Export this page (CSV)</button>}>
        <div className="filters">
          <input placeholder="Account id" inputMode="numeric" value={f.accountId} onChange={e => set('accountId', e.target.value.replace(/\D/g, ''))} aria-label="Account id" />
          <input placeholder="Event code (e.g. EMI_PAYMENT)" value={f.eventCode} onChange={e => set('eventCode', e.target.value.trim().toUpperCase())} aria-label="Event code" />
          <input placeholder="Journal number" value={f.journalNo} onChange={e => set('journalNo', e.target.value.trim())} aria-label="Journal number" />
          <select value={f.branchCode} onChange={e => set('branchCode', e.target.value)} aria-label="Branch">
            <option value="">All branches</option>
            {(branches.data ?? []).map(b => <option key={b.branchCode} value={b.branchCode}>{b.branchCode} - {b.branchName}</option>)}
          </select>
          <label className="inline-label">From <input type="date" value={f.from} onChange={e => set('from', e.target.value)} /></label>
          <label className="inline-label">To <input type="date" value={f.to} onChange={e => set('to', e.target.value)} /></label>
        </div>
        {list.loading && !list.data && <Loading />}
        {list.error && <ErrorBox message={list.error} onRetry={list.reload} />}
        {list.data && (
          <>
            <DataTable columns={cols} rows={rows} rowKey={r => r.journalId} onRowClick={r => setOpenId(openId === r.journalId ? null : r.journalId)}
              rowClass={r => (r.journalId === openId ? 'row-selected' : undefined)} empty="No journals match these filters" />
            <div className="pager">
              <span className="muted">{rows.length === 0 ? 'No rows' : `Rows ${page * PAGE + 1}-${page * PAGE + rows.length}`}</span>
              <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <button className="btn" disabled={rows.length < PAGE} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          </>
        )}
      </Card>
      {openId !== null && (
        <Card title={d ? `Journal ${d.header.journalNo}` : 'Journal'} actions={<button className="btn" onClick={() => setOpenId(null)}>Close</button>}>
          {detail.loading && !d && <Loading />}
          {detail.error && <ErrorBox message={detail.error} onRetry={detail.reload} />}
          {d && (
            <>
              <KeyValues items={[
                ['Event', label(d.header.eventCode)],
                ['Source', `${label(d.header.sourceType)} ${d.header.sourceId ?? ''}`],
                ['Account', d.header.accountId === null ? '-' : (
                  <button className="link-button" onClick={() => nav(`/accounts/${d.header.accountId}`)}>{d.header.accountId}</button>
                )],
                ['Product', d.header.productCode ?? '-'],
                ['Branch / currency', `${d.header.branchCode} / ${d.header.txnCurrency}`],
                ['Business date / value date', `${date(d.header.businessDate)} / ${date(d.header.valueDate)}`],
                ['Template', d.header.templateId === null ? '-' : `#${d.header.templateId} v${d.header.templateVersion ?? '?'}`],
                ['Written', dateTime(d.header.createdAt)],
                ['Reversal', d.header.reversalOfJournalId !== null ? `Reverses journal ${d.header.reversalOfJournalId}`
                  : d.reversedByJournalId !== null ? `Reversed by journal ${d.reversedByJournalId}` : 'None'],
              ]} />
              <DataTable columns={lineCols} rows={d.lines} rowKey={l => l.lineId} empty="No lines" />
            </>
          )}
        </Card>
      )}
    </>
  );
}
