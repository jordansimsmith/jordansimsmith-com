export function formatSetNumber(
  setCode: string,
  collectorNumber: string,
): string {
  return `${setCode.toUpperCase()}#${collectorNumber}`;
}

export function finishNameClass(finish: string): string | undefined {
  if (finish === 'foil') {
    return 'foil-finish';
  }
  if (finish === 'etched') {
    return 'etched-finish';
  }
  return undefined;
}

export function finishNameWeight(finish: string): 500 | 700 {
  return finish === 'normal' ? 500 : 700;
}
