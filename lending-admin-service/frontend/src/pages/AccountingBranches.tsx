import { get } from '../api';
import DataTable, { Col } from '../components/DataTable';
import { Badge, Card, ErrorBox, Loading, PageHeader } from '../components/ui';
import { useLoad } from '../hooks';
import type { BranchInfo } from '../types';

/** The branches accounts are booked at. Read-only here; more are added through the reference API of lending-service. */
export default function AccountingBranches() {
  const { data, error, loading, reload } = useLoad(() => get<BranchInfo[]>('/accounting/branches'), []);
  const cols: Col<BranchInfo>[] = [
    { key: 'c', header: 'Code', render: r => <strong className="mono">{r.branchCode}</strong> },
    { key: 'n', header: 'Name', render: r => r.branchName },
    { key: 'd', header: 'Default', render: r => (r.defaultBranch ? <Badge tone="info">Used when none is given</Badge> : '') },
    { key: 'a', header: 'Status', render: r => <Badge tone={r.active ? 'good' : 'neutral'}>{r.active ? 'Active' : 'Inactive'}</Badge> },
  ];
  return (
    <>
      <PageHeader title="Branches" sub="An account is booked at one branch and every journal for it carries that branch. Routes to GL accounts can be made specific to a branch." />
      <Card>
        {loading && !data && <Loading />}
        {error && <ErrorBox message={error} onRetry={reload} />}
        {data && <DataTable columns={cols} rows={data} rowKey={r => r.branchCode} empty="No branches" />}
      </Card>
    </>
  );
}
