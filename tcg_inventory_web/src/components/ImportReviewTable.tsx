import { useEffect, useRef } from 'react';
import { ActionIcon, Badge, NativeSelect, Table, Text } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import type { Condition, ImportRow, RowDecision } from '../api/client';
import { CONDITIONS } from '../api/client';
import { finishNameWeight, formatSetNumber } from '../domain/card-label';
import { ImportRowPhotoStrip } from './ImportRowPhotoStrip';
import finishClasses from './CardFinishName.module.css';
import classes from './ImportReviewTable.module.css';

const DECISION_COLORS: Record<RowDecision, string> = {
  keep: 'green',
  discard: 'gray',
  review: 'yellow',
};

interface ImportReviewTableProps {
  rows: ImportRow[];
  selectedIndex: number;
  onSelect: (index: number) => void;
  editable?: boolean;
  onConditionChange?: (position: number, condition: Condition) => void;
  onDeleteRow?: (position: number) => void;
  onAddPhoto?: (position: number, file: File) => void;
  onRemovePhoto?: (position: number, photoId: string) => void;
}

export function ImportReviewTable({
  rows,
  selectedIndex,
  onSelect,
  editable = false,
  onConditionChange,
  onDeleteRow,
  onAddPhoto,
  onRemovePhoto,
}: ImportReviewTableProps) {
  const selectedRowRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    selectedRowRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={classes.table}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th ta="right">Position</Table.Th>
          <Table.Th>Name</Table.Th>
          <Table.Th>Set</Table.Th>
          <Table.Th>Finish</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th ta="right">Market</Table.Th>
          <Table.Th ta="right">Suggested</Table.Th>
          <Table.Th>Decision</Table.Th>
          <Table.Th>Reason</Table.Th>
          <Table.Th>Photos</Table.Th>
          {editable && <Table.Th ta="right">Actions</Table.Th>}
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row, index) => {
          const selected = index === selectedIndex;
          const keep = row.decision === 'keep';
          const showPhotos = keep && (editable || row.photos.length > 0);
          return (
            <Table.Tr
              key={row.position}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
              c={keep ? undefined : 'dimmed'}
              onClick={() => onSelect(index)}
            >
              <Table.Td data-field="position" ta="right" c="dimmed">
                {row.position}
              </Table.Td>
              <Table.Td data-field="name">
                <Text
                  component="span"
                  className={keep ? finishClasses[row.finish] : undefined}
                  fw={finishNameWeight(row.finish)}
                >
                  {row.name}
                </Text>
              </Table.Td>
              <Table.Td data-field="set" data-label="Set" title={row.set_name}>
                {formatSetNumber(row.set_code, row.collector_number)}
              </Table.Td>
              <Table.Td data-field="finish" data-label="Finish" tt="capitalize">
                {row.finish}
              </Table.Td>
              <Table.Td data-field="condition" data-label="Condition">
                {editable && onConditionChange ? (
                  <NativeSelect
                    size="xs"
                    value={row.condition}
                    data={CONDITIONS}
                    onChange={(event) =>
                      onConditionChange(
                        row.position,
                        event.currentTarget.value as Condition,
                      )
                    }
                    onClick={(event) => event.stopPropagation()}
                    aria-label={`Condition for row ${row.position}`}
                  />
                ) : (
                  row.condition
                )}
              </Table.Td>
              <Table.Td data-field="market" data-label="Market" ta="right">
                {row.market_price !== null && `$${row.market_price}`}
              </Table.Td>
              <Table.Td
                data-field="suggested"
                data-label="Suggested"
                ta="right"
              >
                {row.suggested_price !== null && `$${row.suggested_price}`}
              </Table.Td>
              <Table.Td data-field="decision">
                {row.decision ? (
                  <Badge variant="light" color={DECISION_COLORS[row.decision]}>
                    {row.decision}
                  </Badge>
                ) : (
                  <Text size="xs" c="dimmed">
                    pending
                  </Text>
                )}
              </Table.Td>
              <Table.Td data-field="reason" data-label="Reason" c="dimmed">
                {row.decision_reason}
              </Table.Td>
              <Table.Td data-field="photos" data-label="Photos">
                {showPhotos && (
                  <ImportRowPhotoStrip
                    position={row.position}
                    photos={row.photos}
                    needsPhotos={row.needs_photos}
                    editable={editable}
                    onAdd={
                      onAddPhoto
                        ? (file) => onAddPhoto(row.position, file)
                        : undefined
                    }
                    onRemove={
                      onRemovePhoto
                        ? (photoId) => onRemovePhoto(row.position, photoId)
                        : undefined
                    }
                  />
                )}
              </Table.Td>
              {editable && onDeleteRow && (
                <Table.Td data-field="actions">
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    size={36}
                    onClick={(event) => {
                      event.stopPropagation();
                      onDeleteRow(row.position);
                    }}
                    aria-label={`Delete row ${row.position}`}
                  >
                    <IconTrash size={14} />
                  </ActionIcon>
                </Table.Td>
              )}
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
