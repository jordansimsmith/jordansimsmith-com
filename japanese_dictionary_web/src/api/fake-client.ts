import type { ApiClient, SCNode, SearchResponse, SearchResult } from './client';

function senseGroup(partOfSpeech: string, glosses: string[]): SCNode {
  return {
    tag: 'div',
    data: { content: 'sense-group' },
    content: [
      {
        tag: 'span',
        data: { class: 'tag', content: 'part-of-speech-info' },
        content: partOfSpeech,
      },
      {
        tag: 'div',
        data: { content: 'sense' },
        content: {
          tag: 'ul',
          data: { content: 'glossary' },
          content: glosses.map<SCNode>((g) => ({ tag: 'li', content: g })),
        },
      },
    ],
  };
}

function attribution(sequence: number): SCNode {
  return {
    tag: 'div',
    data: { content: 'attribution' },
    content: {
      tag: 'a',
      href: `https://www.edrdg.org/jmwsgi/entr.py?svc=jmdict&q=${sequence}`,
      content: 'JMdict',
    },
  };
}

function entry(
  sequence: number,
  partOfSpeech: string,
  glosses: string[],
): SCNode {
  return [
    {
      tag: 'div',
      data: { content: 'entry' },
      content: [senseGroup(partOfSpeech, glosses), attribution(sequence)],
    },
  ];
}

type FormCellClass =
  | 'form-pri'
  | 'form-rare'
  | 'form-irr'
  | 'form-old'
  | 'form-out'
  | 'form-valid';

interface FormsRow {
  reading: string;
  cells: ({ class: FormCellClass; title: string } | null)[];
}

function formsBlock(
  kanjiHeaders: ({ tag: 'special'; symbol: string; title: string } | string)[],
  rows: FormsRow[],
): SCNode {
  return {
    tag: 'li',
    data: { content: 'forms' },
    content: [
      {
        tag: 'span',
        title: 'spelling and reading variants',
        data: { class: 'tag', content: 'forms-label' },
        content: 'forms',
      },
      {
        tag: 'table',
        content: [
          {
            tag: 'tr',
            data: { content: 'forms-header-row' },
            content: [
              { tag: 'th' },
              ...kanjiHeaders.map<SCNode>((h) =>
                typeof h === 'string'
                  ? { tag: 'th', content: h }
                  : {
                      tag: 'th',
                      content: {
                        tag: 'span',
                        title: h.title,
                        data: { class: 'form-special' },
                        content: h.symbol,
                      },
                    },
              ),
            ],
          },
          ...rows.map<SCNode>((row) => ({
            tag: 'tr',
            content: [
              { tag: 'th', content: row.reading },
              ...row.cells.map<SCNode>((cell) =>
                cell === null
                  ? { tag: 'td' }
                  : {
                      tag: 'td',
                      data: { class: cell.class },
                      content: { tag: 'span', title: cell.title },
                    },
              ),
            ],
          })),
        ],
      },
    ],
  };
}

function senseGroupLi(partOfSpeech: string, glosses: string[]): SCNode {
  return {
    tag: 'li',
    data: { content: 'sense-group' },
    content: [
      {
        tag: 'span',
        data: { class: 'tag', content: 'part-of-speech-info' },
        content: partOfSpeech,
      },
      {
        tag: 'ul',
        data: { content: 'glossary' },
        content: glosses.map<SCNode>((g) => ({ tag: 'li', content: g })),
      },
    ],
  };
}

function entryWithForms(
  sequence: number,
  partOfSpeech: string,
  glosses: string[],
  forms: SCNode,
): SCNode {
  return [
    {
      tag: 'ul',
      lang: 'ja',
      data: { content: 'sense-groups' },
      content: [senseGroupLi(partOfSpeech, glosses), forms],
    },
    {
      tag: 'div',
      data: { content: 'attribution' },
      content: {
        tag: 'a',
        href: `https://www.edrdg.org/jmwsgi/entr.py?svc=jmdict&q=${sequence}`,
        content: 'JMdict',
      },
    },
  ];
}

const fixtures: SearchResult[] = [
  {
    sequence: 1362360,
    expression: '新聞',
    reading: 'しんぶん',
    reading_romaji: 'shinbun',
    frequency_rank: 412,
    pitch: 0,
    glossary_raw: entry(1362360, 'noun', ['newspaper']),
  },
  {
    sequence: 1351150,
    expression: '新しい',
    reading: 'あたらしい',
    reading_romaji: 'atarashii',
    frequency_rank: 198,
    pitch: 4,
    glossary_raw: entry(1351150, 'i-adjective', ['new', 'novel', 'fresh']),
  },
  {
    sequence: 1608090,
    expression: '新橋',
    reading: 'しんばし',
    reading_romaji: 'shinbashi',
    frequency_rank: 33448,
    pitch: 0,
    glossary_raw: entry(1608090, 'noun', ['Shinbashi (Tokyo)']),
  },
  {
    sequence: 1362650,
    expression: '新年',
    reading: 'しんねん',
    reading_romaji: 'shinnen',
    frequency_rank: 5021,
    pitch: 1,
    glossary_raw: entry(1362650, 'noun', ['new year']),
  },
  {
    sequence: 1362490,
    expression: '新幹線',
    reading: 'しんかんせん',
    reading_romaji: 'shinkansen',
    frequency_rank: 7820,
    pitch: 3,
    glossary_raw: entry(1362490, 'noun', ['Shinkansen', 'bullet train']),
  },
  {
    sequence: 1320470,
    expression: '心',
    reading: 'こころ',
    reading_romaji: 'kokoro',
    frequency_rank: 105,
    pitch: 2,
    glossary_raw: entry(1320470, 'noun', [
      'mind',
      'heart',
      'spirit',
      'feelings',
    ]),
  },
  {
    sequence: 1358280,
    expression: '食べる',
    reading: 'たべる',
    reading_romaji: 'taberu',
    frequency_rank: 76,
    pitch: 2,
    glossary_raw: entry(1358280, 'ichidan verb', ['to eat']),
  },
  {
    sequence: 1578850,
    expression: '行く',
    reading: 'いく',
    reading_romaji: 'iku',
    frequency_rank: 38,
    pitch: 0,
    glossary_raw: entry(1578850, 'godan verb', ['to go', 'to move']),
  },
  {
    sequence: 1259290,
    expression: '見る',
    reading: 'みる',
    reading_romaji: 'miru',
    frequency_rank: 47,
    pitch: 1,
    glossary_raw: entry(1259290, 'ichidan verb', [
      'to see',
      'to look at',
      'to watch',
    ]),
  },
  {
    sequence: 1547720,
    expression: '来る',
    reading: 'くる',
    reading_romaji: 'kuru',
    frequency_rank: 38,
    pitch: 1,
    glossary_raw: entry(1547720, 'irregular verb', ['to come', 'to arrive']),
  },
  {
    sequence: 1591730,
    expression: '知る',
    reading: 'しる',
    reading_romaji: 'shiru',
    frequency_rank: 89,
    pitch: 0,
    glossary_raw: entry(1591730, 'godan verb', [
      'to know',
      'to be aware of',
      'to be acquainted with',
    ]),
  },
  {
    sequence: 1310460,
    expression: '死ぬ',
    reading: 'しぬ',
    reading_romaji: 'shinu',
    frequency_rank: 1240,
    pitch: 0,
    glossary_raw: entry(1310460, 'godan verb', ['to die', 'to pass away']),
  },
  {
    sequence: 1362050,
    expression: '信じる',
    reading: 'しんじる',
    reading_romaji: 'shinjiru',
    frequency_rank: 932,
    pitch: 3,
    glossary_raw: entry(1362050, 'ichidan verb', [
      'to believe',
      'to place trust in',
    ]),
  },
  {
    sequence: 1346610,
    expression: '静か',
    reading: 'しずか',
    reading_romaji: 'shizuka',
    frequency_rank: 2105,
    pitch: 1,
    glossary_raw: entry(1346610, 'na-adjective', ['quiet', 'silent', 'calm']),
  },
  {
    sequence: 1049180,
    expression: 'コーヒー',
    reading: 'コーヒー',
    reading_romaji: 'koohii',
    frequency_rank: 1854,
    pitch: 3,
    glossary_raw: entry(1049180, 'noun', ['coffee']),
  },
  {
    sequence: 1080370,
    expression: 'テレビ',
    reading: 'テレビ',
    reading_romaji: 'terebi',
    frequency_rank: 542,
    pitch: 1,
    glossary_raw: entry(1080370, 'noun', ['television', 'TV']),
  },
  {
    sequence: 1101410,
    expression: 'パソコン',
    reading: 'パソコン',
    reading_romaji: 'pasokon',
    frequency_rank: 1612,
    pitch: 0,
    glossary_raw: entry(1101410, 'noun', ['personal computer', 'PC']),
  },
  {
    sequence: 1149830,
    expression: 'アメリカ',
    reading: 'アメリカ',
    reading_romaji: 'amerika',
    frequency_rank: 401,
    pitch: 0,
    glossary_raw: entry(1149830, 'noun', ['America', 'United States']),
  },
  {
    sequence: 1481490,
    expression: '桜',
    reading: 'さくら',
    reading_romaji: 'sakura',
    frequency_rank: 2890,
    pitch: 0,
    glossary_raw: [
      {
        tag: 'div',
        data: { content: 'entry' },
        content: [
          senseGroup('noun', ['cherry tree', 'cherry blossom']),
          {
            tag: 'div',
            data: { content: 'extra-info' },
            content: {
              tag: 'img',
              src: 'jitendex/graphics/sakura.png',
              alt: 'cherry blossom',
              data: { class: 'gloss-image' },
            },
          },
          attribution(1481490),
        ],
      },
    ],
  },
  {
    sequence: 1921570,
    expression: '葉節点',
    reading: 'はせってん',
    reading_romaji: 'hasetten',
    frequency_rank: null,
    pitch: null,
    glossary_raw: entry(1921570, 'noun', ['leaf node']),
  },
  {
    sequence: 1922000,
    expression: '範疇文法',
    reading: 'はんちゅうぶんぽう',
    reading_romaji: 'hanchuubunpou',
    frequency_rank: null,
    pitch: null,
    glossary_raw: entry(1922000, 'noun', ['categorial grammar']),
  },
  {
    sequence: 1923000,
    expression: '反対称的',
    reading: 'はんたいしょうてき',
    reading_romaji: 'hantaishouteki',
    frequency_rank: null,
    pitch: null,
    glossary_raw: entry(1923000, 'na-adjective', ['antisymmetric']),
  },
  {
    sequence: 1208730,
    expression: '学校',
    reading: 'がっこう',
    reading_romaji: 'gakkou',
    frequency_rank: 286,
    pitch: 0,
    glossary_raw: entry(1208730, 'noun', ['school']),
  },
  {
    sequence: 1457730,
    expression: '図書館',
    reading: 'としょかん',
    reading_romaji: 'toshokan',
    frequency_rank: 4118,
    pitch: 2,
    glossary_raw: entry(1457730, 'noun', ['library']),
  },
  {
    sequence: 1442600,
    expression: '電車',
    reading: 'でんしゃ',
    reading_romaji: 'densha',
    frequency_rank: 1308,
    pitch: 0,
    glossary_raw: entry(1442600, 'noun', ['train', 'electric train']),
  },
  {
    sequence: 1464530,
    expression: '日本',
    reading: 'にほん',
    reading_romaji: 'nihon',
    frequency_rank: 25,
    pitch: 2,
    glossary_raw: entry(1464530, 'noun', ['Japan']),
  },
  {
    sequence: 1464540,
    expression: '日本語',
    reading: 'にほんご',
    reading_romaji: 'nihongo',
    frequency_rank: 612,
    pitch: 0,
    glossary_raw: entry(1464540, 'noun', ['Japanese language']),
  },
  {
    sequence: 1414250,
    expression: '大学',
    reading: 'だいがく',
    reading_romaji: 'daigaku',
    frequency_rank: 215,
    pitch: 0,
    glossary_raw: entry(1414250, 'noun', ['university', 'college']),
  },
  {
    sequence: 1387990,
    expression: '先生',
    reading: 'せんせい',
    reading_romaji: 'sensei',
    frequency_rank: 184,
    pitch: 3,
    glossary_raw: entry(1387990, 'noun', ['teacher', 'master', 'doctor']),
  },
  {
    sequence: 1305380,
    expression: '仕舞う',
    reading: 'しまう',
    reading_romaji: 'shimau',
    frequency_rank: 487,
    pitch: 0,
    glossary_raw: entryWithForms(
      1305380,
      'godan verb',
      ['to finish', 'to stop', 'to end', 'to put an end to'],
      formsBlock(
        [
          {
            tag: 'special',
            symbol: '\u2205',
            title: 'no associated kanji forms',
          },
          '仕舞う',
          '終う',
          '了う',
          '蔵う',
        ],
        [
          {
            reading: 'しまう',
            cells: [
              { class: 'form-pri', title: 'high priority form' },
              { class: 'form-rare', title: 'rarely used form' },
              { class: 'form-rare', title: 'rarely used form' },
              { class: 'form-rare', title: 'rarely used form' },
              { class: 'form-rare', title: 'rarely used form' },
            ],
          },
        ],
      ),
    ),
  },
  {
    sequence: 1591600,
    expression: '神社',
    reading: 'じんじゃ',
    reading_romaji: 'jinja',
    frequency_rank: 1809,
    pitch: 1,
    glossary_raw: [
      {
        tag: 'div',
        data: { content: 'entry' },
        content: [
          senseGroup('noun', ['Shinto shrine']),
          {
            tag: 'div',
            data: { content: 'xref' },
            content: [
              'see also: ',
              {
                tag: 'a',
                href: '?query=寺',
                content: '寺',
              },
            ],
          },
          attribution(1591600),
        ],
      },
    ],
  },
];

function normaliseQuery(q: string): string {
  return q.toLowerCase().trim();
}

function matches(result: SearchResult, q: string): boolean {
  if (result.expression.startsWith(q) || result.reading.startsWith(q)) {
    return true;
  }
  return result.reading_romaji.startsWith(normaliseQuery(q));
}

function compareResults(a: SearchResult, b: SearchResult): number {
  const aRank = a.frequency_rank;
  const bRank = b.frequency_rank;
  if (aRank === null && bRank === null) {
    return a.sequence - b.sequence;
  }
  if (aRank === null) {
    return 1;
  }
  if (bRank === null) {
    return -1;
  }
  if (aRank !== bRank) {
    return aRank - bRank;
  }
  return a.sequence - b.sequence;
}

const FAKE_LATENCY_MS = 250;
const FAKE_BOOKMARK_LATENCY_MS = 50;

export function createFakeClient(): ApiClient {
  const bookmarks = new Map<number, number>();
  let nextCreatedAt = 1700000000;

  return {
    async search(q: string): Promise<SearchResponse> {
      const trimmed = q.trim();
      if (trimmed.length === 0) {
        return { results: [] };
      }
      await new Promise((resolve) => setTimeout(resolve, FAKE_LATENCY_MS));
      const matched = fixtures.filter((r) => matches(r, trimmed));
      const sorted = [...matched].sort(compareResults).slice(0, 10);
      return { results: sorted };
    },

    async findBookmarks(): Promise<{ sequences: number[] }> {
      await new Promise((resolve) =>
        setTimeout(resolve, FAKE_BOOKMARK_LATENCY_MS),
      );
      const sequences = [...bookmarks.entries()]
        .sort((a, b) => {
          if (a[1] !== b[1]) {
            return b[1] - a[1];
          }
          return a[0] - b[0];
        })
        .map(([sequence]) => sequence);
      return { sequences };
    },

    async createBookmark(sequence: number): Promise<void> {
      if (!Number.isInteger(sequence) || sequence <= 0) {
        throw new Error('sequence must be a positive integer');
      }
      await new Promise((resolve) =>
        setTimeout(resolve, FAKE_BOOKMARK_LATENCY_MS),
      );
      nextCreatedAt += 1;
      bookmarks.set(sequence, nextCreatedAt);
    },

    async deleteBookmark(sequence: number): Promise<void> {
      if (!Number.isInteger(sequence) || sequence <= 0) {
        throw new Error('sequence must be a positive integer');
      }
      await new Promise((resolve) =>
        setTimeout(resolve, FAKE_BOOKMARK_LATENCY_MS),
      );
      bookmarks.delete(sequence);
    },
  };
}
