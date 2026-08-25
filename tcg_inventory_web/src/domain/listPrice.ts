export type ListComparison = 'below' | 'at' | 'above';

function moneyCents(value: string): number {
  return Math.round(Number(value) * 100);
}

function formatMoney(cents: number): string {
  return (cents / 100).toFixed(2);
}

export function listedLineTotal(listedPrice: string, quantity: number): string {
  return formatMoney(moneyCents(listedPrice) * quantity);
}

export function compareToList(offered: string, listed: string): ListComparison {
  const offeredCents = moneyCents(offered);
  const listedCents = moneyCents(listed);
  if (offeredCents < listedCents) {
    return 'below';
  }
  if (offeredCents > listedCents) {
    return 'above';
  }
  return 'at';
}

export function formatVsList(offered: string, listed: string): string {
  const listedCents = moneyCents(listed);
  const percent = Math.round(
    ((moneyCents(offered) - listedCents) / listedCents) * 100,
  );
  const sign = percent > 0 ? '+' : '−';
  return `${sign}${Math.abs(percent)}% vs list`;
}
