import { useEffect, useRef } from 'react';
import { Table } from '@mantine/core';
import type { ImportSummary } from '../api/client';
import { ImportStatusBadge } from './ImportStatusBadge';

interface ImportTableProps {
  imports: ImportSummary[];
  selectedIndex: number;
  onOpen: (importSummary: ImportSummary) => void;
}

export function ImportTable({
  imports,
  selectedIndex,
  onOpen,
}: ImportTableProps) {
  const selectedRowRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    selectedRowRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  return (
    <Table
      striped
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Filename</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th ta="right">Progress</Table.Th>
          <Table.Th ta="right">Keep</Table.Th>
          <Table.Th ta="right">Discard</Table.Th>
          <Table.Th ta="right">Review</Table.Th>
          <Table.Th>Uploaded</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {imports.map((importSummary, index) => {
          const selected = index === selectedIndex;
          const appraised =
            importSummary.keep_count +
            importSummary.discard_count +
            importSummary.review_count;
          return (
            <Table.Tr
              key={importSummary.import_id}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
              bg={selected ? 'var(--mantine-color-blue-light)' : undefined}
              onClick={() => onOpen(importSummary)}
              style={{ cursor: 'pointer' }}
            >
              <Table.Td fw={500}>{importSummary.filename}</Table.Td>
              <Table.Td>
                <ImportStatusBadge importSummary={importSummary} />
              </Table.Td>
              <Table.Td ta="right">
                {appraised}/{importSummary.row_count}
              </Table.Td>
              <Table.Td ta="right">{importSummary.keep_count}</Table.Td>
              <Table.Td ta="right">{importSummary.discard_count}</Table.Td>
              <Table.Td ta="right">{importSummary.review_count}</Table.Td>
              <Table.Td>
                {new Date(importSummary.created_at * 1000).toLocaleString()}
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
