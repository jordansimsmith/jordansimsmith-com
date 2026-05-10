import {
  Container,
  Paper,
  Title,
  TextInput,
  PasswordInput,
  Button,
  Stack,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useNavigate } from 'react-router-dom';
import { setSession } from '../auth/session';

interface LoginFormValues {
  username: string;
  password: string;
}

export function LoginPage() {
  const navigate = useNavigate();

  const form = useForm<LoginFormValues>({
    initialValues: {
      username: '',
      password: '',
    },
    validate: {
      username: (value) => (value.trim() ? null : 'Username is required'),
      password: (value) => (value ? null : 'Password is required'),
    },
  });

  const handleSubmit = (values: LoginFormValues) => {
    setSession(values.username.trim(), values.password);
    navigate('/search');
  };

  return (
    <Container size={400} py="xl">
      <Paper shadow="sm" p="xl" radius="md" withBorder>
        <form onSubmit={form.onSubmit(handleSubmit)}>
          <Stack>
            <Title order={2} ta="center">
              Japanese dictionary
            </Title>

            <TextInput
              label="Username"
              placeholder="Enter your username"
              autoComplete="username"
              {...form.getInputProps('username')}
            />

            <PasswordInput
              label="Password"
              placeholder="Enter your password"
              autoComplete="current-password"
              {...form.getInputProps('password')}
            />

            <Button type="submit" fullWidth>
              Log in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Container>
  );
}
