import type { ReactNode } from 'react';
import { Box, Group, Stack, Text, Title } from '@mantine/core';

interface PageHeaderProps {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({ title, description, actions }: PageHeaderProps) {
  return (
    <Box component="header">
      <Group justify="space-between" align="flex-start" gap="md" wrap="wrap">
        <Stack gap={4} maw={720}>
          <Title
            order={1}
            fz="xl"
            lh={1.2}
            style={{ overflowWrap: 'anywhere' }}
          >
            {title}
          </Title>
          {description && (
            <Text size="sm" c="dimmed">
              {description}
            </Text>
          )}
        </Stack>
        {actions && <Box>{actions}</Box>}
      </Group>
    </Box>
  );
}
