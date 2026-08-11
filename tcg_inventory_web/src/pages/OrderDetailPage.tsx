import { Title } from '@mantine/core';
import { useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();

  return (
    <AppShellLayout>
      <Title order={2}>Order {orderId}</Title>
    </AppShellLayout>
  );
}
