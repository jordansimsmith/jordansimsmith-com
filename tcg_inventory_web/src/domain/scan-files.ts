export const MAX_SCAN_FILES = 200;
export const MAX_SCAN_FILE_BYTES = 10 * 1024 * 1024;

const JPEG_FILENAME_PATTERN = /\.(?:jpe?g)$/i;

function compareBytes(left: Uint8Array, right: Uint8Array): number {
  const length = Math.min(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const difference = left[index] - right[index];
    if (difference !== 0) {
      return difference;
    }
  }
  return left.length - right.length;
}

export function compareScanFilenames(left: string, right: string): number {
  const encoder = new TextEncoder();
  return compareBytes(encoder.encode(left), encoder.encode(right));
}

export function sortScanFiles(files: readonly File[]): File[] {
  return files
    .map((file, index) => ({ file, index }))
    .sort((left, right) => {
      const comparison = compareScanFilenames(left.file.name, right.file.name);
      return comparison === 0 ? left.index - right.index : comparison;
    })
    .map(({ file }) => file);
}

export function validateScanFiles(files: readonly File[]): string[] {
  const errors = new Set<string>();

  if (files.length === 0) {
    errors.add('Choose at least one JPEG file.');
  }
  if (files.length > MAX_SCAN_FILES) {
    errors.add(`Choose no more than ${MAX_SCAN_FILES} JPEG files.`);
  }

  const filenames = new Set<string>();
  const duplicateFilenames = new Set<string>();
  for (const file of files) {
    if (filenames.has(file.name)) {
      duplicateFilenames.add(file.name);
    }
    filenames.add(file.name);

    if (
      !JPEG_FILENAME_PATTERN.test(file.name) ||
      (file.type !== '' && file.type !== 'image/jpeg')
    ) {
      errors.add('Only .jpg and .jpeg files are supported.');
    }
    if (file.size === 0) {
      errors.add('Files must not be empty.');
    }
    if (file.size > MAX_SCAN_FILE_BYTES) {
      errors.add('Each file must be 10 MiB or smaller.');
    }
  }

  if (duplicateFilenames.size > 0) {
    errors.add('File names must be unique.');
  }

  return [...errors];
}
