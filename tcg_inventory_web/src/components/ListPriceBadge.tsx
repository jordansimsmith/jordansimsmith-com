import { Badge } from '@mantine/core';
import { compareToList, formatVsList } from '../domain/listPrice';

const COMPARISON_COLORS = {
  below: 'red',
  above: 'green',
} as const;

interface ListPriceBadgeProps {
  offered: string;
  listed: string;
}

export function ListPriceBadge({ offered, listed }: ListPriceBadgeProps) {
  const comparison = compareToList(offered, listed);
  if (comparison === 'at') {
    return null;
  }
  return (
    <Badge variant="light" color={COMPARISON_COLORS[comparison]}>
      {formatVsList(offered, listed)}
    </Badge>
  );
}
