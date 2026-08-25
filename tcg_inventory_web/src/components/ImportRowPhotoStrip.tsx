import type { CSSProperties, ReactNode } from 'react';
import { ActionIcon, Badge, Group, Image } from '@mantine/core';
import { IconCamera, IconX } from '@tabler/icons-react';
import type { RowPhoto } from '../api/client';

const MAX_PHOTOS = 5;
const THUMB_SIZE = 40;

const hiddenFileInputStyle: CSSProperties = {
  position: 'absolute',
  width: 1,
  height: 1,
  opacity: 0,
  overflow: 'hidden',
};

interface ImportRowPhotoStripProps {
  position: number;
  photos: RowPhoto[];
  needsPhotos: boolean;
  editable: boolean;
  onAdd?: (file: File) => void;
  onRemove?: (photoId: string) => void;
}

export function ImportRowPhotoStrip({
  position,
  photos,
  needsPhotos,
  editable,
  onAdd,
  onRemove,
}: ImportRowPhotoStripProps) {
  const canAdd = editable && onAdd && photos.length < MAX_PHOTOS;

  return (
    <Group gap={6} wrap="nowrap" onClick={(event) => event.stopPropagation()}>
      {photos.map((photo) => (
        <div
          key={photo.photo_id}
          style={{
            position: 'relative',
            width: THUMB_SIZE,
            height: THUMB_SIZE,
            flexShrink: 0,
          }}
        >
          <Image
            src={photo.url}
            alt={`Listing photo ${photo.photo_id}`}
            w={THUMB_SIZE}
            h={THUMB_SIZE}
            radius="sm"
            fit="cover"
          />
          {editable && onRemove && (
            <ActionIcon
              variant="filled"
              color="dark"
              size="xs"
              radius="xl"
              aria-label={`Remove photo ${photo.photo_id}`}
              onClick={() => onRemove(photo.photo_id)}
              style={{ position: 'absolute', top: 2, right: 2 }}
            >
              <IconX size={10} />
            </ActionIcon>
          )}
        </div>
      ))}
      {canAdd && (
        <AddPhotoControl position={position} onAdd={onAdd}>
          <ActionIcon component="span" variant="subtle" size="sm" color="gray">
            <IconCamera size={16} />
          </ActionIcon>
        </AddPhotoControl>
      )}
      {needsPhotos && photos.length === 0 && (
        <Badge variant="light" color="orange">
          Needs photos
        </Badge>
      )}
    </Group>
  );
}

function AddPhotoControl({
  position,
  onAdd,
  children,
}: {
  position: number;
  onAdd: (file: File) => void;
  children: ReactNode;
}) {
  return (
    <label style={{ display: 'inline-flex', cursor: 'pointer' }}>
      {children}
      <input
        type="file"
        accept="image/*"
        aria-label={`Add photo to row ${position}`}
        style={hiddenFileInputStyle}
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = '';
          if (file) {
            onAdd(file);
          }
        }}
      />
    </label>
  );
}
