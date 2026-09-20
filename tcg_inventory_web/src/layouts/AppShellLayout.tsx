import { useState } from 'react';
import { NavLink, Stack, Text } from '@mantine/core';
import {
  IconCards,
  IconCamera,
  IconChartBar,
  IconFileImport,
  IconPackage,
  IconSettings,
} from '@tabler/icons-react';
import { Layout } from '@jordansimsmith_com/ui';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getSession, clearSession } from '../auth/session';
import classes from './AppShellLayout.module.css';

const WORKSPACE_LINKS = [
  { label: 'Inventory', to: '/inventory', icon: IconCards },
  { label: 'Scans', to: '/scan', icon: IconCamera },
  { label: 'Imports', to: '/imports', icon: IconFileImport },
  { label: 'Orders', to: '/orders', icon: IconPackage },
  { label: 'Reports', to: '/reports', icon: IconChartBar },
];
const SETTINGS_LINK = {
  label: 'Settings',
  to: '/settings',
  icon: IconSettings,
};

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

  const renderNavLink = (link: (typeof WORKSPACE_LINKS)[number]) => {
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
        aria-current={active ? 'page' : undefined}
        mih={40}
        className={classes.navLink}
        onClick={() => setNavbarOpened(false)}
      />
    );
  };

  return (
    <Layout
      appTitle="TCG inventory"
      username={session?.username ?? null}
      onLogout={handleLogout}
      navbar={
        <Stack gap={4}>
          <Text
            c="gray.6"
            fz="0.6875rem"
            fw={650}
            tt="uppercase"
            className={classes.sectionLabel}
          >
            Workspace
          </Text>
          {WORKSPACE_LINKS.map(renderNavLink)}
          <div className={classes.divider} />
          {renderNavLink(SETTINGS_LINK)}
        </Stack>
      }
      navbarOpened={navbarOpened}
      onToggleNavbar={() => setNavbarOpened((opened) => !opened)}
    >
      {children}
    </Layout>
  );
}
