import { AppShell, Group, Title, Button, Text } from '@mantine/core';

interface LayoutProps {
  appTitle: string;
  username: string | null;
  onLogout: () => void;
  children: React.ReactNode;
}

export function Layout({
  appTitle,
  username,
  onLogout,
  children,
}: LayoutProps) {
  return (
    <AppShell header={{ height: { base: 60 } }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Title order={3} style={{ whiteSpace: 'nowrap' }}>
            {appTitle}
          </Title>
          {username && (
            <Group gap="sm" wrap="nowrap">
              <Text size="sm" c="dimmed" visibleFrom="sm">
                {username}
              </Text>
              <Button variant="subtle" size="sm" onClick={onLogout}>
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
