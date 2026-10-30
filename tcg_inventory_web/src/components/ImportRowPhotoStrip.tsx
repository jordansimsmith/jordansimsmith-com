import type { CSSProperties, ReactNode } from 'react';
import { ActionIcon, Badge, Group, Image } from '@mantine/core';
import { IconCamera, IconX } from '@tabler/icons-react';
import classes from './ImportRowPhotoStrip.module.css';

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
  photos: string[];
  photoUrls: ReadonlyMap<string, string>;
  needsPhotos: boolean;
  editable: boolean;
  onAdd?: (file: File) => void;
  onRemove?: (photoId: string) => void;
}

export function ImportRowPhotoStrip({
  position,
  photos,
  photoUrls,
  needsPhotos,
  editable,
  onAdd,
  onRemove,
}: ImportRowPhotoStripProps) {
  const canAdd = editable && onAdd && photos.length < MAX_PHOTOS;
  const showNeedsPhotos = needsPhotos && photos.length === 0;

  return (
    <Group gap={6} wrap="wrap" onClick={(event) => event.stopPropagation()}>
      {(canAdd || showNeedsPhotos) && (
        <Group gap={6} wrap="nowrap" preventGrowOverflow={false}>
          {canAdd && (
            <AddPhotoControl position={position} onAdd={onAdd}>
              <ActionIcon
                component="span"
                variant="subtle"
                size={36}
                color="gray"
                aria-label={`Add listing photo for row ${position}`}
                title={`Add listing photo for row ${position}`}
              >
                <IconCamera size={16} />
              </ActionIcon>
            </AddPhotoControl>
          )}
          {showNeedsPhotos && (
            <Badge variant="light" color="orange">
              Needs photos
            </Badge>
          )}
        </Group>
      )}
      {photos.map((photoId) => (
        <div
          key={photoId}
          style={{
            position: 'relative',
            width: THUMB_SIZE,
            height: THUMB_SIZE,
            flexShrink: 0,
          }}
        >
          <Image
            src={photoUrls.get(photoId)}
            alt={`Listing photo ${photoId}`}
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
              aria-label={`Remove photo ${photoId}`}
              onClick={() => onRemove(photoId)}
              style={{ position: 'absolute', top: 2, right: 2 }}
            >
              <IconX size={10} />
            </ActionIcon>
          )}
        </div>
      ))}
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
    <label
      className={classes.upload}
      style={{ display: 'inline-flex', cursor: 'pointer' }}
    >
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
