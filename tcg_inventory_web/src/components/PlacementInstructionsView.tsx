import { Button, Group, Paper, Stack, Text, Title } from '@mantine/core';
import type { ConfirmImportResponse } from '../api/client';

interface PlacementInstructionsViewProps {
  result: ConfirmImportResponse;
  onDone: () => void;
}

export function PlacementInstructionsView({
  result,
  onDone,
}: PlacementInstructionsViewProps) {
  return (
    <Stack gap="md" maw={480}>
      <Title order={3}>Placement instructions</Title>
      {result.unit_count === 0 ? (
        <Text>No cards to place — the import had no keep rows.</Text>
      ) : (
        <>
          <Text size="lg">
            Place {result.unit_count}{' '}
            {result.unit_count === 1 ? 'card' : 'cards'}:
          </Text>
          {result.placement_instructions.map((instruction) => (
            <Paper key={instruction.block} withBorder p="lg" radius="md">
              <Group justify="space-between" align="baseline">
                <Text fz={36} fw={700}>
                  {instruction.block}
                </Text>
                <Text fz="lg">
                  {instruction.unit_count}{' '}
                  {instruction.unit_count === 1 ? 'card' : 'cards'}
                </Text>
              </Group>
              <Text c="dimmed">
                {instruction.from_location} through {instruction.to_location}
              </Text>
            </Paper>
          ))}
          <Text size="sm" c="dimmed">
            Slot the stack in order — placement order matches location order.
          </Text>
        </>
      )}
      <Button size="lg" onClick={onDone}>
        Done
      </Button>
    </Stack>
  );
}
