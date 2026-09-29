export type CardImageSize = 'small' | 'normal';

const MAGIC_GAME_ID = 'mtg';
const MAGIC_EXTERNAL_SOURCE = 'scryfall';

export function cardImageUrl(
  game: string,
  externalSource: string,
  externalId: string,
  size: CardImageSize,
): string {
  if (game !== MAGIC_GAME_ID || externalSource !== MAGIC_EXTERNAL_SOURCE) {
    throw new Error(
      `Unsupported card image identity: ${game}/${externalSource}`,
    );
  }
  return `https://api.scryfall.com/cards/${encodeURIComponent(externalId)}?format=image&version=${size}`;
}
