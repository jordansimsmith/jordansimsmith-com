import { Container, Title, Text, Button, Group, Stack } from '@mantine/core';
import { useNavigate } from 'react-router-dom';
import { clearSession, getSession } from '../auth/session';

export function BooksPage() {
  const navigate = useNavigate();
  const session = getSession();

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  return (
    <Container py="xl">
      <Stack>
        <Group justify="space-between">
          <Title order={1}>Books</Title>
          <Button variant="subtle" onClick={handleLogout}>
            Log out
          </Button>
        </Group>
        <Text c="dimmed">Signed in as {session?.username}</Text>
      </Stack>
    </Container>
  );
}
