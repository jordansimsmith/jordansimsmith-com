import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { BooksPage } from './pages/BooksPage';
import { AddBookPage } from './pages/AddBookPage';
import { getSession } from './auth/session';
import { LibraryStatsProvider } from './layouts/library-stats';

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
    return <Navigate to="/books" replace />;
  }
  return <LoginPage />;
}

export function App() {
  return (
    <LibraryStatsProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HomeRoute />} />
          <Route
            path="/books"
            element={
              <RequireAuth>
                <BooksPage />
              </RequireAuth>
            }
          />
          <Route
            path="/books/add"
            element={
              <RequireAuth>
                <AddBookPage />
              </RequireAuth>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </LibraryStatsProvider>
  );
}
