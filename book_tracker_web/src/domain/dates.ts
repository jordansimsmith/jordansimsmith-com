const MONTH_NAMES = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

export function formatMonth(yearMonth: string): string {
  const [yearString, monthString] = yearMonth.split('-');
  const monthIndex = Number(monthString) - 1;
  if (monthIndex < 0 || monthIndex > 11) {
    return yearMonth;
  }
  return `${MONTH_NAMES[monthIndex]} ${yearString}`;
}

export function monthKey(date: string): string {
  return date.slice(0, 7);
}
