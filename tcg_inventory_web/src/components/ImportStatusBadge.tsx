import { Badge } from '@mantine/core';
import type { ImportStatus } from '../api/client';

const STATUS_COLORS: Record<ImportStatus, string> = {
  appraising: 'blue',
  review: 'yellow',
  confirming: 'blue',
  confirmed: 'green',
};

interface ImportStatusBadgeProps {
  status: ImportStatus;
  error?: string | null;
}

export function ImportStatusBadge({ status, error }: ImportStatusBadgeProps) {
  if (error) {
    return (
      <Badge variant="light" color="red">
        failed
      </Badge>
    );
  }
  return (
    <Badge variant="light" color={STATUS_COLORS[status]}>
      {status}
    </Badge>
  );
}
