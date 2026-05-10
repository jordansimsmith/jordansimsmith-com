const SMALL_KANA = new Set('ゃゅょゎァィゥェォャュョヮ');

export function splitMorae(reading: string): string[] {
  const morae: string[] = [];
  for (const ch of reading) {
    if (SMALL_KANA.has(ch) && morae.length > 0) {
      morae[morae.length - 1] += ch;
    } else {
      morae.push(ch);
    }
  }
  return morae;
}
