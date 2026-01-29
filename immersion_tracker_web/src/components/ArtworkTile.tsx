import { Box, Text, Stack } from '@mantine/core';
import { IconPhoto } from '@tabler/icons-react';
import type { TileViewModel } from '../presenters/progress-presenter';

interface ArtworkTileProps {
  tile: TileViewModel;
}

export function ArtworkTile({ tile }: ArtworkTileProps) {
  return (
    <Stack gap={8} align="center">
      <Box
        style={{
          width: '100%',
          aspectRatio: '1 / 1',
          borderRadius: 8,
          overflow: 'hidden',
          backgroundColor: 'var(--mantine-color-gray-1)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {tile.artworkUrl ? (
          <img
            src={tile.artworkUrl}
            alt={tile.name}
            style={{
              width: '100%',
              height: '100%',
              objectFit: 'cover',
            }}
          />
        ) : (
          <IconPhoto
            size={48}
            stroke={1.5}
            color="var(--mantine-color-gray-5)"
          />
        )}
      </Box>
      <Stack gap={2} align="center" style={{ width: '100%' }}>
        <Text
          size="sm"
          fw={500}
          ta="center"
          lineClamp={2}
          style={{ wordBreak: 'break-word' }}
        >
          {tile.name}
        </Text>
        {tile.count !== null && (
          <Text size="xs" c="dimmed">
            {tile.count} {tile.count === 1 ? 'ep' : 'eps'}
          </Text>
        )}
      </Stack>
    </Stack>
  );
}
