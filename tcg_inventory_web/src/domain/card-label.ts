export function formatSetNumber(
  setCode: string,
  collectorNumber: string,
): string {
  return `${setCode.toUpperCase()}#${collectorNumber}`;
}

export function finishNameWeight(finish: string): 500 | 700 {
  return finish === 'normal' ? 500 : 700;
}
