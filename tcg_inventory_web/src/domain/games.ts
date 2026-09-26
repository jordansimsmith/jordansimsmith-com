export const MAGIC_THE_GATHERING = {
  id: 'mtg',
  label: 'Magic: The Gathering',
  externalSource: 'scryfall',
  finishes: ['normal', 'foil', 'etched'],
} as const;

export const GAMES = [MAGIC_THE_GATHERING] as const;

export type GameId = (typeof GAMES)[number]['id'];
export type Finish = (typeof GAMES)[number]['finishes'][number];

export function getGame(gameId: string) {
  const game = GAMES.find(({ id }) => id === gameId);
  if (!game) {
    throw new Error(`Unsupported game: ${gameId}`);
  }
  return game;
}

export function gameLabel(gameId: string): string {
  return getGame(gameId).label;
}
