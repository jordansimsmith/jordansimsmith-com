import { Alert, Text } from '@mantine/core';

interface JobFailureAlertProps {
  title: string;
  error: string | null;
  maw?: number;
}

export function JobFailureAlert({ title, error, maw }: JobFailureAlertProps) {
  // body min-width lets the message truncate instead of overflowing the alert
  return (
    <Alert
      color="red"
      title={title}
      maw={maw}
      py="xs"
      styles={{ body: { minWidth: 0 } }}
    >
      {error && (
        <Text size="sm" truncate>
          {error}
        </Text>
      )}
    </Alert>
  );
}
