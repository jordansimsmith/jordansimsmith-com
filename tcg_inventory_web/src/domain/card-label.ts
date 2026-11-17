export function formatSetNumber(
  setCode: string,
  collectorNumber: string,
): string {
  return `${setCode.toUpperCase()}#${collectorNumber}`;
}
