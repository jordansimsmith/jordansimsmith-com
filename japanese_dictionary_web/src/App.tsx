import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { Container, Title } from '@mantine/core';

function HomeRoute() {
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
