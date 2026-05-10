import { describe, it, expect } from 'vitest';
import { splitMorae } from './morae';

describe('splitMorae', () => {
  it('returns one mora per character for a simple reading', () => {
    expect(splitMorae('たべる')).toEqual(['た', 'べ', 'る']);
  });

  it('groups small ya/yu/yo with the preceding mora', () => {
    expect(splitMorae('としょかん')).toEqual(['と', 'しょ', 'か', 'ん']);
    expect(splitMorae('じゅう')).toEqual(['じゅ', 'う']);
    expect(splitMorae('きょう')).toEqual(['きょ', 'う']);
  });

  it('groups katakana yoon with the preceding mora', () => {
    expect(splitMorae('ジュース')).toEqual(['ジュ', 'ー', 'ス']);
  });

  it('counts small tsu as its own mora', () => {
    expect(splitMorae('がっこう')).toEqual(['が', 'っ', 'こ', 'う']);
  });

  it('counts n as its own mora', () => {
    expect(splitMorae('しんぶん')).toEqual(['し', 'ん', 'ぶ', 'ん']);
  });

  it('counts the long-vowel mark as its own mora', () => {
    expect(splitMorae('コーヒー')).toEqual(['コ', 'ー', 'ヒ', 'ー']);
  });

  it('handles an empty string', () => {
    expect(splitMorae('')).toEqual([]);
  });

  it('does not group a small kana at the start', () => {
    expect(splitMorae('ょう')).toEqual(['ょ', 'う']);
  });
});
