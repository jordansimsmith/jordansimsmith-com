import { Table } from '@mantine/core';
import type { ImportSummary } from '../api/client';
import { ImportStatusBadge } from './ImportStatusBadge';
import classes from './CollectionTable.module.css';
import { useGames } from '../GamesProvider';
import { importDisplayName } from '../domain/import-display-name';

interface ImportTableProps {
  imports: ImportSummary[];
  onOpen: (importSummary: ImportSummary) => void;
}

export function ImportTable({ imports, onOpen }: ImportTableProps) {
  const { getGame } = useGames();
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
          <Table.Th>Import</Table.Th>
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
              {importDisplayName(importSummary.filename)}
            </Table.Td>
            <Table.Td data-field="game" data-label="Game">
              {getGame(importSummary.game).display_name}
            </Table.Td>
            <Table.Td data-field="status" data-label="Status">
              <ImportStatusBadge importSummary={importSummary} />
            </Table.Td>
            <Table.Td ta="right" data-field="rows" data-label="Rows">
              {importSummary.row_count}
            </Table.Td>
            <Table.Td data-field="uploaded" data-label="Uploaded">
              {new Date(importSummary.created_at * 1000).toLocaleString(
                undefined,
                {
                  dateStyle: 'short',
                  timeStyle: 'short',
                  hour12: true,
                },
              )}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
