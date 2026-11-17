import { Text } from '@mantine/core';
import type { Finish, GameId } from '../api/client';
import { useGames } from '../GamesProvider';

const FINISH_COLORS = ['cyan.9', 'indigo.7', 'violet.6', 'teal.9', 'pink.8'];

function getFinishColor(gameId: GameId, finishId: Finish): string {
  const key = JSON.stringify([gameId, finishId]);
  let hash = 2166136261;
  for (let index = 0; index < key.length; index++) {
    hash = Math.imul(hash ^ key.charCodeAt(index), 16777619);
  }
  return FINISH_COLORS[(hash >>> 0) % FINISH_COLORS.length];
}

interface CardNameProps {
  name: string;
  gameId: GameId;
  finishId: Finish;
  highlight?: boolean;
}

export function CardName({
  name,
  gameId,
  finishId,
  highlight = true,
}: CardNameProps) {
  const { getGame } = useGames();
  const emphasized =
    highlight && finishId !== getGame(gameId).default_finish_id;
  return (
    <Text
      component="span"
      inherit
      fw={emphasized ? 700 : 500}
      c={emphasized ? getFinishColor(gameId, finishId) : undefined}
    >
      {name}
    </Text>
  );
}
