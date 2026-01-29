import { useState } from 'react';
import {
  Stack,
  Group,
  Title,
  Text,
  Button,
  Modal,
  SimpleGrid,
  ScrollArea,
} from '@mantine/core';
import { ArtworkTile } from './ArtworkTile';
import type { ContentSectionViewModel } from '../presenters/progress-presenter';

interface ContentTypeSectionProps {
  section: ContentSectionViewModel;
  countLabel: string;
}

export function ContentTypeSection({
  section,
  countLabel,
}: ContentTypeSectionProps) {
  const [modalOpened, setModalOpened] = useState(false);

  return (
    <>
      <Stack gap="md">
        <Group justify="space-between" align="center">
          <Stack gap={2}>
            <Title order={3}>{section.title}</Title>
            <Text size="sm" c="dimmed">
              {section.totalCount.toLocaleString()} {countLabel}
            </Text>
          </Stack>
          {section.allTiles.length > 5 && (
            <Button
              variant="subtle"
              size="sm"
              onClick={() => setModalOpened(true)}
            >
              See all
            </Button>
          )}
        </Group>

        <SimpleGrid cols={{ base: 2, xs: 3, sm: 5 }} spacing="md">
          {section.topTiles.map((tile) => (
            <ArtworkTile key={tile.id} tile={tile} />
          ))}
        </SimpleGrid>
      </Stack>

      <Modal
        opened={modalOpened}
        onClose={() => setModalOpened(false)}
        title={section.title}
        size="lg"
        padding="xl"
      >
        <ScrollArea.Autosize mah="70vh">
          <SimpleGrid cols={{ base: 2, xs: 3, sm: 4 }} spacing="md" pb="md">
            {section.allTiles.map((tile) => (
              <ArtworkTile key={tile.id} tile={tile} />
            ))}
          </SimpleGrid>
        </ScrollArea.Autosize>
      </Modal>
    </>
  );
}
