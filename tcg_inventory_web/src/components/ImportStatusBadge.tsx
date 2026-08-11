import { Badge } from '@mantine/core';
import type { ImportStatus, ImportSummary } from '../api/client';

const STATUS_COLORS: Record<ImportStatus, string> = {
  appraising: 'blue',
  review: 'yellow',
  confirming: 'orange',
  confirmed: 'green',
};

interface ImportStatusBadgeProps {
  importSummary: ImportSummary;
}

export function ImportStatusBadge({ importSummary }: ImportStatusBadgeProps) {
  if (importSummary.appraisal_error) {
    return (
      <Badge variant="light" color="red">
        failed
      </Badge>
    );
  }
  return (
    <Badge variant="light" color={STATUS_COLORS[importSummary.status]}>
      {importSummary.status}
    </Badge>
  );
}
