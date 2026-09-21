export interface ScryfallPrinting {
  id: string;
  name: string;
  set_code: string;
  set_name: string;
  collector_number: string;
  image_url: string;
}

interface ScryfallImageUris {
  normal?: string;
}

interface ScryfallCardFace {
  image_uris?: ScryfallImageUris;
}

interface ScryfallCard {
  id: string;
  name: string;
  set: string;
  set_name: string;
  collector_number: string;
  image_uris?: ScryfallImageUris;
  card_faces?: ScryfallCardFace[];
  prints_search_uri?: string;
}

interface ScryfallPrintingsPage {
  data: ScryfallCard[];
  has_more?: boolean;
  next_page?: string;
}

interface ScryfallAutocompleteResponse {
  data: string[];
}

export interface ScryfallClient {
  getPrintingsForId(cardId: string): Promise<ScryfallPrinting[]>;
  getPrintingsByName(name: string): Promise<ScryfallPrinting[]>;
  autocomplete(query: string, signal?: AbortSignal): Promise<string[]>;
}

type FetchLike = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

const API_URL = 'https://api.scryfall.com';

function imageUrl(card: ScryfallCard): string {
  const image =
    card.image_uris?.normal ?? card.card_faces?.[0]?.image_uris?.normal;
  if (!image) {
    throw new Error(`Scryfall card "${card.name}" has no display image`);
  }
  return image;
}

function normalize(card: ScryfallCard): ScryfallPrinting {
  return {
    id: card.id,
    name: card.name,
    set_code: card.set,
    set_name: card.set_name,
    collector_number: card.collector_number,
    image_url: imageUrl(card),
  };
}

async function readJson<T>(
  fetchImpl: FetchLike,
  url: string,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetchImpl(url, { signal });
  if (!response.ok) {
    throw new Error(`Scryfall request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

async function readAllPrintings(
  fetchImpl: FetchLike,
  card: ScryfallCard,
): Promise<ScryfallPrinting[]> {
  const cards = [card];
  let nextUrl = card.prints_search_uri;
  const visitedPages = new Set<string>();

  while (nextUrl) {
    if (visitedPages.has(nextUrl)) {
      throw new Error('Scryfall pagination repeated a page');
    }
    visitedPages.add(nextUrl);
    const page = await readJson<ScryfallPrintingsPage>(fetchImpl, nextUrl);
    if (!Array.isArray(page.data)) {
      throw new Error('Scryfall pagination returned invalid printing data');
    }
    cards.push(...page.data);
    if (page.has_more) {
      if (!page.next_page) {
        throw new Error('Scryfall pagination response is missing next_page');
      }
      nextUrl = page.next_page;
    } else {
      nextUrl = undefined;
    }
  }

  const seen = new Set<string>();
  return cards
    .filter((candidate) => {
      if (seen.has(candidate.id)) {
        return false;
      }
      seen.add(candidate.id);
      return true;
    })
    .map(normalize);
}

export function createScryfallClient(
  fetchImpl: FetchLike = fetch,
): ScryfallClient {
  return {
    async getPrintingsForId(cardId: string): Promise<ScryfallPrinting[]> {
      const card = await readJson<ScryfallCard>(
        fetchImpl,
        `${API_URL}/cards/${encodeURIComponent(cardId)}`,
      );
      return readAllPrintings(fetchImpl, card);
    },

    async getPrintingsByName(name: string): Promise<ScryfallPrinting[]> {
      const card = await readJson<ScryfallCard>(
        fetchImpl,
        `${API_URL}/cards/named?exact=${encodeURIComponent(name)}`,
      );
      return readAllPrintings(fetchImpl, card);
    },

    async autocomplete(query: string, signal?: AbortSignal): Promise<string[]> {
      const response = await readJson<ScryfallAutocompleteResponse>(
        fetchImpl,
        `${API_URL}/cards/autocomplete?q=${encodeURIComponent(query)}`,
        signal,
      );
      return response.data;
    },
  };
}

export const scryfallClient = createScryfallClient();
