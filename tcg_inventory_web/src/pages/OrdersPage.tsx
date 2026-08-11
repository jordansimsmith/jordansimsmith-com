import { Title } from '@mantine/core';
import { AppShellLayout } from '../layouts/AppShellLayout';

export function OrdersPage() {
  return (
    <AppShellLayout>
      <Title order={2}>Orders</Title>
    </AppShellLayout>
  );
}
