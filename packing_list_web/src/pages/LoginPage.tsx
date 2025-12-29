import { useState } from 'react';
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
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { setSession, clearSession } from '../auth/session';
import { apiClient } from '../api/client';

interface LoginFormValues {
  username: string;
  password: string;
}

export function LoginPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);

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

  const handleSubmit = async (values: LoginFormValues) => {
    setLoading(true);

    setSession(values.username.trim(), values.password);

    try {
      await apiClient.getTemplates();
      navigate('/trips');
    } catch {
      clearSession();
      notifications.show({
        title: 'Login failed',
        message: 'Invalid username or password',
        color: 'red',
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <Container size={400} py="xl">
      <Paper shadow="sm" p="xl" radius="md" withBorder>
        <form onSubmit={form.onSubmit(handleSubmit)}>
          <Stack>
            <Title order={2} ta="center">
              Packing list
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

            <Button type="submit" fullWidth loading={loading}>
              Log in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Container>
  );
}
