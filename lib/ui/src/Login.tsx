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

export interface LoginValues {
  username: string;
  password: string;
}

interface LoginProps {
  appTitle: string;
  onSubmit: (values: LoginValues) => void | Promise<void>;
}

export function Login({ appTitle, onSubmit }: LoginProps) {
  const [loading, setLoading] = useState(false);

  const form = useForm<LoginValues>({
    initialValues: {
      username: '',
      password: '',
    },
    validate: {
      username: (value) => (value.trim() ? null : 'Username is required'),
      password: (value) => (value ? null : 'Password is required'),
    },
  });

  const handleSubmit = async (values: LoginValues) => {
    setLoading(true);
    try {
      await onSubmit(values);
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
              {appTitle}
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
