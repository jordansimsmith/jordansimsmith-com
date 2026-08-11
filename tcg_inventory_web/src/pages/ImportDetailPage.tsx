import { Title } from '@mantine/core';
import { useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';

export function ImportDetailPage() {
  const { importId } = useParams<{ importId: string }>();

  return (
    <AppShellLayout>
      <Title order={2}>Import {importId}</Title>
    </AppShellLayout>
  );
}
