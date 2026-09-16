import { useState } from 'react';
import {
  Box,
  Container,
  Paper,
  Title,
  Text,
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
    <Box
      component="main"
      style={{
        minHeight: '100dvh',
        display: 'flex',
        alignItems: 'center',
        paddingBlock: 'var(--mantine-spacing-xl)',
      }}
    >
      <Container size={420} w="100%">
        <Paper p="xl" radius="md" withBorder>
          <Stack gap="xl">
            <Stack gap={4}>
              <Title order={1} fz="1.5rem" lh={1.2}>
                {appTitle}
              </Title>
              <Text size="sm" c="dimmed">
                Sign in to continue
              </Text>
            </Stack>

            <form onSubmit={form.onSubmit(handleSubmit)}>
              <Stack gap="md">
                <TextInput
                  label="Username"
                  placeholder="Enter your username"
                  autoComplete="username"
                  size="md"
                  {...form.getInputProps('username')}
                />

                <PasswordInput
                  label="Password"
                  placeholder="Enter your password"
                  autoComplete="current-password"
                  size="md"
                  {...form.getInputProps('password')}
                />

                <Button type="submit" fullWidth size="md" loading={loading}>
                  Log in
                </Button>
              </Stack>
            </form>
          </Stack>
        </Paper>
      </Container>
    </Box>
  );
}
