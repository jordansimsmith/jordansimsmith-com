import { Login } from '@jordansimsmith_com/ui';
import type { LoginValues } from '@jordansimsmith_com/ui';
import { useNavigate } from 'react-router-dom';
import { setSession } from '../auth/session';

export function LoginPage() {
  const navigate = useNavigate();

  const handleSubmit = async (values: LoginValues) => {
    setSession(values.username.trim(), values.password);
    navigate('/inventory');
  };

  return <Login appTitle="TCG inventory" onSubmit={handleSubmit} />;
}
