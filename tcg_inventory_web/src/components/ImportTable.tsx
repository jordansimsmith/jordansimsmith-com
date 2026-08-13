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
          <Table.Th ta="right">Rows</Table.Th>
          <Table.Th>Uploaded</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {imports.map((importSummary, index) => {
          const selected = index === selectedIndex;
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
              <Table.Td ta="right">{importSummary.row_count}</Table.Td>
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
