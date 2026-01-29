import { Accordion, Text, Badge, Group, Stack, Button } from '@mantine/core';
import { IconCheck } from '@tabler/icons-react';
import type { TemplatesResponse } from '../api/client';

interface TemplatesPickerProps {
  templates: TemplatesResponse;
  addedVariations: Set<string>;
  onApplyVariation: (variationId: string) => void;
}

export function TemplatesPicker({
  templates,
  addedVariations,
  onApplyVariation,
}: TemplatesPickerProps) {
  return (
    <Accordion>
      {templates.variations.map((variation) => (
        <Accordion.Item
          key={variation.variation_id}
          value={variation.variation_id}
        >
          <Accordion.Control>
            <Group justify="space-between" wrap="nowrap">
              <Group gap="xs">
                <Text>{variation.name}</Text>
                <Badge size="sm" variant="light">
                  {variation.items.length} items
                </Badge>
              </Group>
              {addedVariations.has(variation.variation_id) && (
                <Badge color="teal" size="sm">
                  Added
                </Badge>
              )}
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <Stack gap="xs">
              {[...variation.items]
                .sort((a, b) => a.name.localeCompare(b.name))
                .map((item) => (
                  <Text key={item.name} size="sm">
                    {item.name} {item.quantity > 1 && `(×${item.quantity})`}
                  </Text>
                ))}
              {!addedVariations.has(variation.variation_id) && (
                <Button
                  size="xs"
                  variant="light"
                  leftSection={<IconCheck size={14} />}
                  onClick={() => onApplyVariation(variation.variation_id)}
                >
                  Apply variation
                </Button>
              )}
            </Stack>
          </Accordion.Panel>
        </Accordion.Item>
      ))}
    </Accordion>
  );
}
