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
    <AppShell header={{ height: { base: 60 } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Title order={3} style={{ whiteSpace: 'nowrap' }}>
            Packing list
          </Title>
          {session && (
            <Group gap="sm" wrap="nowrap">
              <Text size="sm" c="dimmed" visibleFrom="sm">
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
