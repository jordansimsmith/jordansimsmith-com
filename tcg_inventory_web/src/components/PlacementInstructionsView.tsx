import {
  Badge,
  Box,
  Button,
  Group,
  Paper,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import type { PlacementInstruction } from '../api/client';
import classes from './PlacementInstructionsView.module.css';

interface PlacementInstructionsViewProps {
  unitCount: number;
  totalSuggestedPrice: string;
  placementInstructions: PlacementInstruction[];
  onBackToImports: () => void;
}

export function PlacementInstructionsView({
  unitCount,
  totalSuggestedPrice,
  placementInstructions,
  onBackToImports,
}: PlacementInstructionsViewProps) {
  return (
    <Paper
      component="section"
      aria-label="Placement sheet"
      withBorder
      radius="md"
      style={{ overflow: 'hidden' }}
    >
      <Stack gap={0}>
        <Box p="md">
          <Group justify="space-between" align="flex-start" gap="md">
            <Stack gap="xs">
              <Group gap="sm">
                <Title order={2} fz="md">
                  Placement sheet
                </Title>
                <Badge variant="light" color="green">
                  Confirmed
                </Badge>
              </Group>
              <Title order={3} fz="xl">
                {unitCount === 0
                  ? 'No cards to place'
                  : `Place ${unitCount} ${unitCount === 1 ? 'card' : 'cards'}`}
              </Title>
            </Stack>
            <Stack gap={2} align="flex-end">
              <Text size="xs" c="dimmed" fw={600} tt="uppercase">
                Total suggested value
              </Text>
              <Text fw={600} className={classes.numeric}>
                ${totalSuggestedPrice}
              </Text>
            </Stack>
          </Group>
          {unitCount === 0 ? (
            <Text size="sm" c="dimmed" mt="sm">
              This import had no keep rows.
            </Text>
          ) : (
            <Text size="sm" c="dimmed" mt="sm">
              Slot the stack in order. Placement order matches location order.
            </Text>
          )}
        </Box>

        {placementInstructions.map((instruction) => (
          <Box key={instruction.block} className={classes.instructionRow}>
            <Group className={classes.blockSummary} gap="md">
              <Text fz="lg" fw={700} className={classes.block}>
                {instruction.block}
              </Text>
              <Text fw={600} className={classes.location}>
                {instruction.from_location === instruction.to_location
                  ? instruction.from_location
                  : `${instruction.from_location} through ${instruction.to_location}`}
              </Text>
              <Text size="sm" c="dimmed" className={classes.count}>
                {instruction.unit_count}{' '}
                {instruction.unit_count === 1 ? 'card' : 'cards'}
              </Text>
            </Group>
            <Text size="sm" className={classes.cardRange}>
              {instruction.from_name === instruction.to_name
                ? instruction.from_name
                : `${instruction.from_name} through ${instruction.to_name}`}
            </Text>
          </Box>
        ))}

        <Group justify="flex-end" p="md" className={classes.footer}>
          <Button variant="default" onClick={onBackToImports}>
            Back to imports
          </Button>
        </Group>
      </Stack>
    </Paper>
  );
}
