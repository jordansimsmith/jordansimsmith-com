import { Anchor, Group } from '@mantine/core';
import { Layout } from '@jordansimsmith_com/ui';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getSession, clearSession } from '../auth/session';

const NAV_LINKS = [
  { label: 'Inventory', to: '/inventory' },
  { label: 'Imports', to: '/imports' },
  { label: 'Orders', to: '/orders' },
  { label: 'Settings', to: '/settings' },
];

interface AppShellLayoutProps {
  children: React.ReactNode;
}

export function AppShellLayout({ children }: AppShellLayoutProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const session = getSession();

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  return (
    <Layout
      appTitle="TCG inventory"
      username={session?.username ?? null}
      onLogout={handleLogout}
    >
      <Group gap="lg" mb="md">
        {NAV_LINKS.map((link) => {
          const active =
            location.pathname === link.to ||
            location.pathname.startsWith(`${link.to}/`);
          return (
            <Anchor
              key={link.to}
              component={Link}
              to={link.to}
              size="sm"
              fw={active ? 700 : undefined}
              c={active ? undefined : 'dimmed'}
            >
              {link.label}
            </Anchor>
          );
        })}
      </Group>
      {children}
    </Layout>
  );
}
