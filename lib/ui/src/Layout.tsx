import { AppShell, Burger, Group, Title, Button, Text } from '@mantine/core';

interface LayoutProps {
  appTitle: string;
  username: string | null;
  onLogout: () => void;
  navbar?: React.ReactNode;
  navbarOpened?: boolean;
  onToggleNavbar?: () => void;
  children: React.ReactNode;
}

export function Layout({
  appTitle,
  username,
  onLogout,
  navbar,
  navbarOpened = false,
  onToggleNavbar,
  children,
}: LayoutProps) {
  return (
    <AppShell
      header={{ height: { base: 60 } }}
      navbar={
        navbar
          ? {
              width: 200,
              breakpoint: 'sm',
              collapsed: { mobile: !navbarOpened },
            }
          : undefined
      }
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            {navbar && (
              <Burger
                opened={navbarOpened}
                onClick={onToggleNavbar}
                hiddenFrom="sm"
                size="sm"
                aria-label="Toggle navigation"
              />
            )}
            <Title order={3} style={{ whiteSpace: 'nowrap' }}>
              {appTitle}
            </Title>
          </Group>
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
      {navbar && <AppShell.Navbar p="xs">{navbar}</AppShell.Navbar>}
      <AppShell.Main>{children}</AppShell.Main>
    </AppShell>
  );
}
