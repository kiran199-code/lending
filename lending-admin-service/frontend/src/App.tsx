import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth';
import Layout from './components/Layout';
import { Loading } from './components/ui';
import AccountDetail from './pages/AccountDetail';
import Accounts from './pages/Accounts';
import Accruals from './pages/Accruals';
import Batch from './pages/Batch';
import Configuration from './pages/Configuration';
import Dashboard from './pages/Dashboard';
import Ledger from './pages/Ledger';
import Login from './pages/Login';
import Maintenance from './pages/Maintenance';
import Overdue from './pages/Overdue';
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
        <Route path="accounts" element={<Accounts />} />
        <Route path="accounts/:id" element={<AccountDetail />} />
        <Route path="upcoming" element={<Upcoming />} />
        <Route path="overdue" element={<Overdue />} />
        <Route path="payments" element={<Payments />} />
        <Route path="accruals" element={<Accruals />} />
        <Route path="ledger" element={<Ledger />} />
        <Route path="batch" element={<Batch />} />
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
