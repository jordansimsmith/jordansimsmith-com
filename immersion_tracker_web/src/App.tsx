import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { ProgressPage } from './pages/ProgressPage';
import { getSession } from './auth/session';

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
    return <Navigate to="/progress" replace />;
  }
  return <LoginPage />;
}

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route
          path="/progress"
          element={
            <RequireAuth>
              <ProgressPage />
            </RequireAuth>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
