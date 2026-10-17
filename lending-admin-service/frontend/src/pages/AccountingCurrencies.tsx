import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { useLoad } from '../hooks';
import type { CurrencyInfo } from '../types';

/** The currencies accounts can be opened in. Read-only here; more are added through the reference API of lending-service. */
export default function AccountingCurrencies() {
  const { data, error, loading, reload } = useLoad(() => get<CurrencyInfo[]>('/accounting/currencies'), []);
  const cols: Col<CurrencyInfo>[] = [
    { key: 'c', header: 'Code', render: r => <strong className="mono">{r.currencyCode}</strong> },
    { key: 'n', header: 'Name', render: r => r.currencyName },
    { key: 'm', header: 'Decimal places', align: 'right', render: r => r.minorUnits },
    { key: 'b', header: 'Base currency', render: r => (r.base ? <Badge tone="info">Books are kept in this</Badge> : '') },
    { key: 'a', header: 'Status', render: r => <Badge tone={r.active ? 'good' : 'neutral'}>{r.active ? 'Active' : 'Inactive'}</Badge> },
  ];
  return (
    <>
      <PageHeader title="Currencies" sub="An account is opened in one currency and every journal for it is posted in that currency. Only the base currency can be active until foreign-currency accounting is switched on." />
      <Card>
        {loading && !data && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && <DataTable columns={cols} rows={data} rowKey={r => r.currencyCode} empty="No currencies" />}
      </Card>
    </>
  );
}
