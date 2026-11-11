export function importDisplayName(
  filename: string | null,
  fallback: string,
): string {
  if (filename === null) {
    return fallback;
  }
  return filename.endsWith('.scan') ? 'Scan' : filename;
}
