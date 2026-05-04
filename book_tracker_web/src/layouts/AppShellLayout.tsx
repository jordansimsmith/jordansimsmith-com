import { AppShell, Badge, Group, Title, Button, Text } from '@mantine/core';
import { useNavigate } from 'react-router-dom';
import { clearSession, getSession } from '../auth/session';
import { useLibraryStats } from './library-stats';

interface AppShellLayoutProps {
  children: React.ReactNode;
}

export function AppShellLayout({ children }: AppShellLayoutProps) {
  const navigate = useNavigate();
  const session = getSession();
  const { rollingCount } = useLibraryStats();

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  return (
    <AppShell header={{ height: { base: 60 } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="md" wrap="nowrap" style={{ minWidth: 0 }}>
            <Title order={3} style={{ whiteSpace: 'nowrap' }}>
              Book tracker
            </Title>
            {rollingCount !== null && (
              <Badge
                size="lg"
                variant="light"
                visibleFrom="xs"
                aria-label={`${rollingCount} books in last 12 months`}
              >
                {rollingCount} in last 12 months
              </Badge>
            )}
          </Group>
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
