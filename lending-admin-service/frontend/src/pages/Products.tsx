import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { count, label, money0 } from '../format';
import { useLoad } from '../hooks';
import type { Product } from '../types';

const range = (min: number | null, max: number | null, fmt: (v: number) => string = String) =>
  min === null && max === null ? 'no limit' : `${min === null ? '-' : fmt(min)} to ${max === null ? '-' : fmt(max)}`;
const pctRange = (min: number | null, max: number | null) => range(min, max, v => `${v}%`);

export default function Products() {
  const { data, error, loading, reload } = useLoad(() => get<Product[]>('/products'), []);

  const cols: Col<Product>[] = [
    { key: 'c', header: 'Product', render: r => (<><strong className="mono">{r.productCode}</strong><div className="muted">{r.productName}</div></>) },
    { key: 't', header: 'Type', render: r => `${label(r.accountType)} | ${label(r.rateType)}` },
    { key: 's', header: 'Schedule / day count', render: r => `${r.scheduleType} | ${r.dayCountConvention.replace('_', '/')}` },
    { key: 'r', header: 'Default rate', align: 'right', render: r => `${r.defaultInterestRate}%` },
    { key: 'b', header: 'Benchmark / spread', render: r => (r.rateType === 'FLOATING' ? `${r.benchmarkRateRef} + ${r.defaultSpread ?? 0}%` : '-') },
    { key: 'rb', header: 'Rate band', render: r => pctRange(r.minInterestRate, r.maxInterestRate) },
    { key: 'sb', header: 'Spread band', render: r => (r.rateType === 'FLOATING' ? pctRange(r.minSpread, r.maxSpread) : '-') },
    { key: 'ab', header: 'Amount band', render: r => range(r.minSanctionedAmount, r.maxSanctionedAmount, money0) },
    { key: 'tb', header: 'Tenure (months)', render: r => range(r.minTenureMonths, r.maxTenureMonths) },
    { key: 'st', header: 'Status', render: r => <Badge tone={r.status === 'ACTIVE' ? 'good' : 'neutral'}>{label(r.status)}</Badge> },
    { key: 'n', header: 'Accounts', align: 'right', render: r => count(r.accountCount) },
  ];

  return (
    <>
      <PageHeader title="Loan products" sub="Default terms and limits applied when an account is opened. Maintained through lending-service (Swagger: /api/v1/loan-products)." />
      {loading && !data && <Loading />}
      {error && <ErrorBox message={error} onRetry={reload} />}
      {data && (
        <Card title={`Products (${data.length})`}>
          <DataTable columns={cols} rows={data} rowKey={r => r.productId} empty="No products defined" />
        </Card>
      )}
    </>
  );
}
