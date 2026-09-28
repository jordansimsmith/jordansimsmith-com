export function importDisplayName(filename: string): string {
  return filename.endsWith('.scan') ? 'Scan' : filename;
}
