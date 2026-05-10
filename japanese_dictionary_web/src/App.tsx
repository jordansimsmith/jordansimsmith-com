import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Container, Title } from '@mantine/core';
import { LoginPage } from './pages/LoginPage';
import { getSession } from './auth/session';

function HomeRoute() {
  const session = getSession();
  if (!session) {
    return <LoginPage />;
  }
  return (
    <Container size="md" py="xl">
      <Title order={1}>Japanese dictionary</Title>
    </Container>
  );
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
