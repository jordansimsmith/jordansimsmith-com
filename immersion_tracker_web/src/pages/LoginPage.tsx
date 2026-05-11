import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { Login, type LoginValues } from '@jordansimsmith_com/ui';
import { setSession, clearSession } from '../auth/session';
import { apiClient } from '../api/client';

export function LoginPage() {
  const navigate = useNavigate();

  const handleSubmit = async (values: LoginValues) => {
    setSession(values.username.trim(), values.password);

    try {
      await apiClient.getProgress();
      navigate('/progress');
    } catch {
      clearSession();
      notifications.show({
        title: 'Login failed',
        message: 'Invalid username or password',
        color: 'red',
      });
    }
  };

  return <Login appTitle="Immersion tracker" onSubmit={handleSubmit} />;
}
