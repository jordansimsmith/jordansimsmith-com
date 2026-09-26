import { MAGIC_THE_GATHERING } from './games';

export type CardImageSize = 'small' | 'normal';

export function cardImageUrl(
  game: string,
  externalSource: string,
  externalId: string,
  size: CardImageSize,
): string {
  if (
    game !== MAGIC_THE_GATHERING.id ||
    externalSource !== MAGIC_THE_GATHERING.externalSource
  ) {
    throw new Error(
      `Unsupported card image identity: ${game}/${externalSource}`,
    );
  }
  return `https://api.scryfall.com/cards/${encodeURIComponent(externalId)}?format=image&version=${size}`;
}
