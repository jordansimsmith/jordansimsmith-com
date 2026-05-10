import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { LoginPage } from './pages/LoginPage';
import { SearchPage } from './pages/SearchPage';
import { getSession } from './auth/session';

function HomeRoute() {
  const session = getSession();
  if (!session) {
    return <LoginPage />;
  }
  return <SearchPage />;
}

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomeRoute />} />
      </Routes>
    </BrowserRouter>
  );
}
