import { Table } from '@mantine/core';
import type { ImportSummary } from '../api/client';
import { ImportStatusBadge } from './ImportStatusBadge';
import classes from './CollectionTable.module.css';
import { gameLabel } from '../domain/games';

interface ImportTableProps {
  imports: ImportSummary[];
  onOpen: (importSummary: ImportSummary) => void;
}

export function ImportTable({ imports, onOpen }: ImportTableProps) {
  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={`${classes.table} ${classes.imports}`}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Filename</Table.Th>
          <Table.Th>Game</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th ta="right">Rows</Table.Th>
          <Table.Th>Uploaded</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {imports.map((importSummary) => (
          <Table.Tr
            key={importSummary.import_id}
            onClick={() => onOpen(importSummary)}
            style={{ cursor: 'pointer' }}
          >
            <Table.Td fw={500} data-field="filename">
              {importSummary.filename}
            </Table.Td>
            <Table.Td data-field="game" data-label="Game">
              {gameLabel(importSummary.game)}
            </Table.Td>
            <Table.Td data-field="status" data-label="Status">
              <ImportStatusBadge importSummary={importSummary} />
            </Table.Td>
            <Table.Td ta="right" data-field="rows" data-label="Rows">
              {importSummary.row_count}
            </Table.Td>
            <Table.Td data-field="uploaded" data-label="Uploaded">
              {new Date(importSummary.created_at * 1000).toLocaleString()}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
