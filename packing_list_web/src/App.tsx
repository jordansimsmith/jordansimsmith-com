import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { TripsPage } from './pages/TripsPage';
import { CreateTripPage } from './pages/CreateTripPage';
import { TripPage } from './pages/TripPage';
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
    return <Navigate to="/trips" replace />;
  }
  return <LoginPage />;
}

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
        <Route
          path="/trips"
          element={
            <RequireAuth>
              <TripsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/trips/create"
          element={
            <RequireAuth>
              <CreateTripPage />
            </RequireAuth>
          }
        />
        <Route
          path="/trips/:tripId"
          element={
            <RequireAuth>
              <TripPage />
            </RequireAuth>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
