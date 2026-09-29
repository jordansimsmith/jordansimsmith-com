import type { Finish, Game, GameFinish, GameId } from '../api/client';

export function getGame(games: Game[], gameId: GameId): Game {
  const game = games.find(({ id }) => id === gameId);
  if (!game) {
    throw new Error(`Unsupported game: ${gameId}`);
  }
  return game;
}

export function getFinish(game: Game, finishId: Finish): GameFinish {
  const finish = game.finishes.find(({ id }) => id === finishId);
  if (!finish) {
    throw new Error(`Unsupported finish for ${game.id}: ${finishId}`);
  }
  return finish;
}
