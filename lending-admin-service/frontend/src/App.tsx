import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import Layout from './components/Layout';
import { Loading } from './components/ui';
import AccountDetail from './pages/AccountDetail';
import Accounts from './pages/Accounts';
import Accruals from './pages/Accruals';
import AccountingBranches from './pages/AccountingBranches';
import AccountingCurrencies from './pages/AccountingCurrencies';
import AccountingExceptions from './pages/AccountingExceptions';
import AccountingJournals from './pages/AccountingJournals';
import AccountingReconciliation from './pages/AccountingReconciliation';
import AccountingTrialBalance from './pages/AccountingTrialBalance';
import Batch from './pages/Batch';
import BulkOperations from './pages/BulkOperations';
import Configuration from './pages/Configuration';
import CreateLoan from './pages/CreateLoan';
import Dashboard from './pages/Dashboard';
import DisburseLoan from './pages/DisburseLoan';
import EndOfDay from './pages/EndOfDay';
import Foreclosure from './pages/Foreclosure';
import Ledger from './pages/Ledger';
import Login from './pages/Login';
import Maintenance from './pages/Maintenance';
import Overdue from './pages/Overdue';
import PartPayment from './pages/PartPayment';
import PayInstalment from './pages/PayInstalment';
import Payments from './pages/Payments';
import Products from './pages/Products';
import Upcoming from './pages/Upcoming';

function Gate() {
  const { me, checking } = useAuth();
  if (checking) return <Loading what="Starting" />;
  if (!me) return <Login />;
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="products" element={<Products />} />
        <Route path="ops/create" element={<CreateLoan />} />
        <Route path="ops/disburse" element={<DisburseLoan />} />
        <Route path="ops/instalment" element={<PayInstalment />} />
        <Route path="ops/part-payment" element={<PartPayment />} />
        <Route path="ops/foreclosure" element={<Foreclosure />} />
        <Route path="ops/bulk" element={<BulkOperations />} />
        <Route path="accounts" element={<Accounts />} />
        <Route path="accounts/:id" element={<AccountDetail />} />
        <Route path="upcoming" element={<Upcoming />} />
        <Route path="overdue" element={<Overdue />} />
        <Route path="payments" element={<Payments />} />
        <Route path="accruals" element={<Accruals />} />
        <Route path="ledger" element={<Ledger />} />
        <Route path="accounting/journals" element={<AccountingJournals />} />
        <Route path="accounting/trial-balance" element={<AccountingTrialBalance />} />
        <Route path="accounting/exceptions" element={<AccountingExceptions />} />
        <Route path="accounting/reconciliation" element={<AccountingReconciliation />} />
        <Route path="accounting/currencies" element={<AccountingCurrencies />} />
        <Route path="accounting/branches" element={<AccountingBranches />} />
        <Route path="batch" element={<Batch />} />
        <Route path="eod" element={<EndOfDay />} />
        <Route path="config" element={<Configuration />} />
        <Route path="maintenance" element={<Maintenance />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <HashRouter>
      <AuthProvider>
        <Gate />
      </AuthProvider>
    </HashRouter>
  );
}
