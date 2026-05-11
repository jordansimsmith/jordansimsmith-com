import { Layout } from '@jordansimsmith_com/ui';
import { useNavigate } from 'react-router-dom';
import { clearSession, getSession } from '../auth/session';

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
    <Layout
      appTitle="Book tracker"
      username={session?.username ?? null}
      onLogout={handleLogout}
    >
      {children}
    </Layout>
  );
}
