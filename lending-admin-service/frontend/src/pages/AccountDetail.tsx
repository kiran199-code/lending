import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { download, get } from '../api';
import { useAuth } from '../auth';
import { DailyArea } from '../components/charts';
import DataTable, { Col } from '../components/DataTable';
import { Card, Empty, ErrorBox, InstallmentBadge, KeyValues, Kpi, Loading, PageHeader, PaymentBadge, StatusBadge, Tabs } from '../components/ui';
import { date, dateTime, downloadCsv, label, money, pct } from '../format';
import { useLoad } from '../hooks';
import type { AccountDetail as Detail, AccrualResponse, AccrualRow, MortgageDetail, PaymentResponse, PaymentRow, ScheduleResponse, ScheduleRow } from '../types';

type TabId = 'overview' | 'schedule' | 'accruals' | 'payments' | 'mortgage';

export default function AccountDetail() {
  const { id } = useParams();
  const { me } = useAuth();
  const [tab, setTab] = useState<TabId>('overview');
  const [pdfBusy, setPdfBusy] = useState(false);
  const [pdfError, setPdfError] = useState<string | null>(null);
  const { data: a, error, loading, reload } = useLoad(() => get<Detail>(`/accounts/${id}`), [id]);

  if (loading && !a) return <Loading what="Loading account" />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!a) return null;

  const tabs: { id: TabId; label: string }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'schedule', label: 'Amortization' },
    { id: 'accruals', label: 'Accruals' },
    { id: 'payments', label: 'Payments' },
    ...(a.accountType === 'MORTGAGE' ? [{ id: 'mortgage' as TabId, label: 'Mortgage' }] : []),
  ];

  async function statement() {
    setPdfBusy(true);
    setPdfError(null);
    try { await download(`/accounts/${a!.accountId}/statement`, `loan-statement-${a!.accountNumber}.pdf`); }
    catch (e) { setPdfError(e instanceof Error ? e.message : 'Download failed'); }
    finally { setPdfBusy(false); }
  }

  return (
    <>
      <p className="crumb"><Link to="/accounts">Accounts</Link> / {a.accountNumber}</p>
      <PageHeader
        title={<>{a.accountNumber} <StatusBadge status={a.accountStatus} /></>}
        sub={`Customer ${a.customerId} | ${a.productCode} ${a.accountType === 'MORTGAGE' ? 'mortgage' : 'unsecured'} loan | account id ${a.accountId}`}
        actions={
          <>
            <button className="btn" onClick={reload}>Refresh</button>
            {me?.statementEnabled && <button className="btn btn-primary" disabled={pdfBusy} onClick={statement}>{pdfBusy ? 'Preparing...' : 'Statement PDF'}</button>}
          </>
        }
      />
      {pdfError && <ErrorBox message={pdfError} />}
      <Tabs tabs={tabs} value={tab} onChange={setTab} />
      {tab === 'overview' && <Overview a={a} />}
      {tab === 'schedule' && <Schedule id={a.accountId} accountNumber={a.accountNumber} />}
      {tab === 'accruals' && <Accruals id={a.accountId} />}
      {tab === 'payments' && <Payments id={a.accountId} accountNumber={a.accountNumber} />}
      {tab === 'mortgage' && <Mortgage id={a.accountId} />}
    </>
  );
}

// ------------------------------------------------------------------ overview

function Overview({ a }: { a: Detail }) {
  const f = a.figures;
  const progress = f.installmentsTotal ? Math.round((f.installmentsPaid / f.installmentsTotal) * 100) : 0;
  return (
    <>
      <div className="kpis">
        <Kpi title="Outstanding principal" value={money(f.outstandingPrincipal)} />
        <Kpi title="Overdue" value={money(f.overdueAmount)} sub={f.overdueInstallments ? `${f.overdueInstallments} instalments, ${f.daysPastDue} days past due` : 'Nothing overdue'} tone={f.overdueInstallments ? 'bad' : 'good'} />
        <Kpi title="Next EMI" value={money(f.nextEmiAmount)} sub={f.nextDueDate ? `Due ${date(f.nextDueDate)}` : 'No instalment pending'} />
        <Kpi title="Total paid" value={money(f.totalPaid)} />
        <Kpi title="Interest accrued" value={money(f.totalAccrued)} sub={f.lastAccrualDate ? `Last accrual ${date(f.lastAccrualDate)}` : 'No accrual yet'} />
      </div>
      <Card title="Repayment progress">
        <div className="progress" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
          <div style={{ width: `${progress}%` }} />
        </div>
        <p className="muted">{f.installmentsPaid} of {f.installmentsTotal || a.tenureMonths} instalments paid ({progress}%)</p>
      </Card>
      <div className="grid-2">
        <Card title="Loan terms">
          <KeyValues items={[
            ['Sanctioned amount', money(a.sanctionedAmount)],
            ['Disbursed amount', money(a.disbursedAmount)],
            ['Tenure', `${a.tenureMonths} months`],
            ['EMI', money(a.emiAmount)],
            ['First EMI date', date(a.emiStartDate)],
            ['Rate type', label(a.rateType)],
            ['Interest rate', pct(a.interestRate)],
            ['Benchmark / spread', a.benchmarkRateRef ? `${a.benchmarkRateRef} + ${pct(a.spread)}` : '-'],
            ['Day count', a.dayCountConvention],
          ]} />
        </Card>
        <Card title="Account">
          <KeyValues items={[
            ['Account number', a.accountNumber],
            ['Customer id', a.customerId],
            ['Sanction reference', a.sanctionRefId],
            ['Product', a.productCode],
            ['Type', label(a.accountType)],
            ['Status', <StatusBadge key="s" status={a.accountStatus} />],
            ['Mortgage id', a.mortgageId ?? '-'],
            ['Created', dateTime(a.createdAt)],
            ['Last updated', dateTime(a.updatedAt)],
          ]} />
        </Card>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ schedule

function Schedule({ id, accountNumber }: { id: number; accountNumber: string }) {
  const [version, setVersion] = useState<string>('');
  const { data, error, loading, reload } = useLoad(() => get<ScheduleResponse>(`/accounts/${id}/schedule`, { version }), [id, version]);

  if (loading && !data) return <Loading what="Loading schedule" />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  const t = data.totals;

  const cols: Col<ScheduleRow>[] = [
    { key: 'n', header: '#', render: r => r.installmentNumber },
    { key: 'due', header: 'Due date', render: r => date(r.dueDate) },
    { key: 'p', header: 'Principal', align: 'right', render: r => money(r.principal) },
    { key: 'i', header: 'Interest', align: 'right', render: r => money(r.interest) },
    { key: 'e', header: 'EMI', align: 'right', render: r => <strong>{money(r.emi)}</strong> },
    { key: 'b', header: 'Balance after', align: 'right', render: r => money(r.outstandingBalance) },
    { key: 's', header: 'Status', render: r => <InstallmentBadge status={r.status} daysPastDue={r.daysPastDue} /> },
    { key: 'v', header: 'Version', align: 'center', render: r => `v${r.scheduleVersion}` },
  ];

  function exportCsv() {
    downloadCsv(`schedule-${accountNumber}.csv`,
      ['Installment', 'Due date', 'Principal', 'Interest', 'EMI', 'Balance after', 'Status', 'Days past due', 'Schedule version'],
      data!.rows.map(r => [r.installmentNumber, r.dueDate, r.principal, r.interest, r.emi, r.outstandingBalance, r.status, r.daysPastDue, r.scheduleVersion]));
  }

  return (
    <>
      <div className="kpis">
        <Kpi title="Total EMI" value={money(t.totalEmi)} sub={`Principal ${money(t.totalPrincipal)}`} />
        <Kpi title="Total interest" value={money(t.totalInterest)} />
        <Kpi title="Paid" value={money(t.paidEmi)} sub={`${t.paid} instalments`} tone="good" />
        <Kpi title="Pending" value={money(t.pendingEmi)} sub={`${t.pending} instalments`} />
        <Kpi title="Overdue" value={money(t.overdueEmi)} sub={`${t.overdue} instalments`} tone={t.overdue ? 'bad' : undefined} />
      </div>
      <Card
        title="Amortization schedule"
        actions={
          <>
            <select value={version} onChange={e => setVersion(e.target.value)} aria-label="Schedule version">
              <option value="">Combined (latest of each instalment)</option>
              {data.versions.map(v => <option key={v.version} value={v.version}>Version {v.version} ({v.installments} instalments)</option>)}
            </select>
            <button className="btn" onClick={exportCsv}>Export CSV</button>
          </>
        }
      >
        {data.versions.length > 1 && (
          <p className="muted">This loan has {data.versions.length} schedule versions. A part-payment or rate reset creates a new version covering the remaining instalments; older versions are kept for audit.</p>
        )}
        <DataTable columns={cols} rows={data.rows} rowKey={r => `${r.installmentNumber}-${r.scheduleVersion}`}
          rowClass={r => (r.status !== 'PAID' && r.daysPastDue > 0 ? 'row-bad' : undefined)}
          empty="No schedule yet. It is generated when the loan is disbursed." />
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ accruals

function Accruals({ id }: { id: number }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const { data, error, loading, reload } = useLoad(() => get<AccrualResponse>(`/accounts/${id}/accruals`, { from, to }), [id, from, to]);

  const cols: Col<AccrualRow>[] = [
    { key: 'd', header: 'Business date', render: r => date(r.businessDate) },
    { key: 'o', header: 'Outstanding principal', align: 'right', render: r => money(r.outstandingPrincipal) },
    { key: 'a', header: 'Interest accrued', align: 'right', render: r => <strong>{money(r.accruedAmount)}</strong> },
  ];

  return (
    <Card
      title="Daily interest accruals"
      actions={
        <>
          <label className="inline-label">From <input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
          <label className="inline-label">To <input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
          <button className="btn" onClick={() => { setFrom(''); setTo(''); }}>All dates</button>
        </>
      }
    >
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <>
          <p><strong>{money(data.total)}</strong> <span className="muted">accrued over {data.days} {data.days === 1 ? 'day' : 'days'}</span></p>
          {data.rows.length > 1 && <DailyArea name="Interest accrued per day" data={[...data.rows].reverse().map(r => ({ date: r.businessDate, amount: r.accruedAmount, count: 1 }))} />}
          <DataTable columns={cols} rows={data.rows} rowKey={r => r.businessDate} empty="No accruals for this account in the selected range" />
        </>
      )}
    </Card>
  );
}

// ------------------------------------------------------------------ payments

function Payments({ id, accountNumber }: { id: number; accountNumber: string }) {
  const { data, error, loading, reload } = useLoad(() => get<PaymentResponse>(`/accounts/${id}/payments`), [id]);
  if (loading && !data) return <Loading what="Loading payments" />;
  if (error) return <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;

  const cols: Col<PaymentRow>[] = [
    { key: 'd', header: 'Value date', render: r => date(r.valueDate) },
    { key: 't', header: 'Type', render: r => label(r.type) },
    { key: 'n', header: 'Instalment', align: 'center', render: r => r.installmentNumber ?? '-' },
    { key: 'a', header: 'Amount', align: 'right', render: r => <strong>{money(r.amount)}</strong> },
    { key: 'p', header: 'Principal', align: 'right', render: r => money(r.principalPaid) },
    { key: 'i', header: 'Interest', align: 'right', render: r => money(r.interestPaid) },
    { key: 'pn', header: 'Penal interest', align: 'right', render: r => money(r.penalInterestPaid) },
    { key: 'c', header: 'Channel', render: r => label(r.channel) },
    { key: 's', header: 'Status', render: r => <PaymentBadge status={r.status} /> },
    { key: 'k', header: 'Reference', render: r => <span className="mono">{r.idempotencyKey ?? '-'}</span> },
  ];

  function exportCsv() {
    downloadCsv(`payments-${accountNumber}.csv`,
      ['Transaction', 'Value date', 'Type', 'Instalment', 'Amount', 'Principal', 'Interest', 'Penal interest', 'Channel', 'Status', 'Reference'],
      data!.rows.map(r => [r.transactionId, r.valueDate, r.type, r.installmentNumber, r.amount, r.principalPaid, r.interestPaid, r.penalInterestPaid, r.channel, r.status, r.idempotencyKey]));
  }

  return (
    <>
      <div className="kpis">
        <Kpi title="Received" value={money(data.totalReceived)} tone="good" />
        <Kpi title="Principal" value={money(data.principalReceived)} />
        <Kpi title="Interest" value={money(data.interestReceived)} />
        <Kpi title="Failed attempts" value={data.failed} tone={data.failed ? 'warn' : undefined} />
      </div>
      <Card title="Payment history" actions={<button className="btn" onClick={exportCsv}>Export CSV</button>}>
        <DataTable columns={cols} rows={data.rows} rowKey={r => r.transactionId} empty="No payments received yet" />
      </Card>
    </>
  );
}

// ------------------------------------------------------------------ mortgage

function Mortgage({ id }: { id: number }) {
  const { data, error, loading, reload } = useLoad(() => get<MortgageDetail>(`/accounts/${id}/mortgage`), [id]);
  if (loading && !data) return <Loading what="Loading mortgage" />;
  if (error) return error.includes('no mortgage') ? <Empty>No mortgage has been registered for this account yet.</Empty> : <ErrorBox message={error} onRetry={reload} />;
  if (!data) return null;
  return (
    <>
      <div className="grid-2">
        <Card title="Property and charge">
          <KeyValues items={[
            ['Property type', label(data.propertyType)],
            ['Address', data.propertyAddress],
            ['Valuation', money(data.valuationAmount)],
            ['Valued on', date(data.valuationDate)],
            ['Valuer', data.valuerRef ?? '-'],
            ['LTV ratio', pct(data.ltvRatio)],
          ]} />
        </Card>
        <Card title="Security status">
          <KeyValues items={[
            ['Charge status', label(data.chargeStatus)],
            ['CERSAI registration', data.cersaiRegistrationRef ?? '-'],
            ['Mortgage deed', data.mortgageDeedRef ?? '-'],
            ['Insurance policy', data.insurancePolicyRef ?? '-'],
            ['Legal verification', label(data.legalVerificationStatus)],
          ]} />
        </Card>
      </div>
      <Card title="Valuation history">
        <DataTable
          columns={[
            { key: 'd', header: 'Date', render: r => date(r.valuationDate) },
            { key: 'a', header: 'Valuation', align: 'right', render: r => money(r.valuationAmount) },
            { key: 't', header: 'Type', render: r => label(r.valuationType) },
            { key: 'v', header: 'Valuer', render: r => r.valuerRef ?? '-' },
          ]}
          rows={data.valuations} rowKey={r => `${r.valuationDate}-${r.valuationAmount}`} empty="No revaluations recorded" />
      </Card>
    </>
  );
}
