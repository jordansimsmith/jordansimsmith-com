import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Box } from '@mantine/core';
import { LoginPage } from './pages/LoginPage';
import { SearchPage } from './pages/SearchPage';
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
    return <Navigate to="/search" replace />;
  }
  return <LoginPage />;
}

export function App() {
  return (
    <BrowserRouter>
      <Box mih="100vh">
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route
            path="/search"
            element={
              <RequireAuth>
                <SearchPage />
              </RequireAuth>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Box>
    </BrowserRouter>
  );
}
