import { AppShell, Group, Title, Button, Text } from '@mantine/core';
import { useNavigate } from 'react-router-dom';
import { getSession, clearSession } from '../auth/session';

interface AppShellLayoutProps {
  children: React.ReactNode;
}

export function AppShellLayout({ children }: AppShellLayoutProps) {
  const navigate = useNavigate();
  const session = getSession();

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  return (
    <AppShell header={{ height: 60 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={3}>Immersion tracker</Title>
          {session && (
            <Group gap="sm">
              <Text size="sm" c="dimmed">
                {session.username}
              </Text>
              <Button variant="subtle" size="sm" onClick={handleLogout}>
                Log out
              </Button>
            </Group>
          )}
        </Group>
      </AppShell.Header>
      <AppShell.Main>{children}</AppShell.Main>
    </AppShell>
  );
}
