import {
  BrowserRouter,
  Routes,
  Route,
  Navigate,
  Outlet,
} from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { InventoryPage } from './pages/InventoryPage';
import { SkuDetailPage } from './pages/SkuDetailPage';
import { ImportsPage } from './pages/ImportsPage';
import { ImportDetailPage } from './pages/ImportDetailPage';
import { OrdersPage } from './pages/OrdersPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { ReportsPage } from './pages/ReportsPage';
import { SettingsPage } from './pages/SettingsPage';
import { ScanPage } from './pages/ScanPage';
import { ScanDetailPage } from './pages/ScanDetailPage';
import { getSession } from './auth/session';
import { GamesProvider } from './GamesProvider';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = getSession();
  if (!session) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

function HomeRoute() {
  const session = getSession();
  if (session) {
    return <Navigate to="/inventory" replace />;
  }
  return <LoginPage />;
}

function AuthenticatedWorkspace() {
  return (
    <RequireAuth>
      <GamesProvider>
        <Outlet />
      </GamesProvider>
    </RequireAuth>
  );
}

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route element={<AuthenticatedWorkspace />}>
          <Route path="/inventory" element={<InventoryPage />} />
          <Route path="/inventory/:skuId" element={<SkuDetailPage />} />
          <Route path="/imports" element={<ImportsPage />} />
          <Route path="/imports/:importId" element={<ImportDetailPage />} />
          <Route path="/scans" element={<ScanPage />} />
          <Route path="/scans/:scanId" element={<ScanDetailPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/orders/:orderId" element={<OrderDetailPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
