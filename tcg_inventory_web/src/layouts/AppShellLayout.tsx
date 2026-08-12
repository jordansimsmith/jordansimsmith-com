import { useState } from 'react';
import { NavLink } from '@mantine/core';
import {
  IconCards,
  IconFileImport,
  IconPackage,
  IconSettings,
} from '@tabler/icons-react';
import { Layout } from '@jordansimsmith_com/ui';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getSession, clearSession } from '../auth/session';

const NAV_LINKS = [
  { label: 'Inventory', to: '/inventory', icon: IconCards },
  { label: 'Imports', to: '/imports', icon: IconFileImport },
  { label: 'Orders', to: '/orders', icon: IconPackage },
  { label: 'Settings', to: '/settings', icon: IconSettings },
];

interface AppShellLayoutProps {
  children: React.ReactNode;
}

export function AppShellLayout({ children }: AppShellLayoutProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const session = getSession();
  const [navbarOpened, setNavbarOpened] = useState(false);

  const handleLogout = () => {
    clearSession();
    navigate('/');
  };

  const navbar = NAV_LINKS.map((link) => {
    const active =
      location.pathname === link.to ||
      location.pathname.startsWith(`${link.to}/`);
    return (
      <NavLink
        key={link.to}
        component={Link}
        to={link.to}
        label={link.label}
        leftSection={<link.icon size={18} stroke={1.5} />}
        active={active}
        onClick={() => setNavbarOpened(false)}
      />
    );
  });

  return (
    <Layout
      appTitle="TCG inventory"
      username={session?.username ?? null}
      onLogout={handleLogout}
      navbar={navbar}
      navbarOpened={navbarOpened}
      onToggleNavbar={() => setNavbarOpened((opened) => !opened)}
    >
      {children}
    </Layout>
  );
}
