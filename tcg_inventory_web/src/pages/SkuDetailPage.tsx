import { Title } from '@mantine/core';
import { useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';

export function SkuDetailPage() {
  const { skuId } = useParams<{ skuId: string }>();

  return (
    <AppShellLayout>
      <Title order={2}>SKU {skuId}</Title>
    </AppShellLayout>
  );
}
