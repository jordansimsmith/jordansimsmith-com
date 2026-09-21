import { describe, expect, it } from 'vitest';
import {
  MAX_SCAN_FILE_BYTES,
  compareScanFilenames,
  sortScanFiles,
  validateScanFiles,
} from './scan-files';

function jpeg(name: string, contents = 'jpeg', type = 'image/jpeg'): File {
  return new File([contents], name, { type });
}

describe('scan files', () => {
  it('sorts names by UTF-8 byte order rather than numeric order', () => {
    const files = [jpeg('2.jpg'), jpeg('10.jpg'), jpeg('1.jpg')];

    expect(sortScanFiles(files).map((file) => file.name)).toEqual([
      '1.jpg',
      '10.jpg',
      '2.jpg',
    ]);
    expect(compareScanFilenames('A.jpg', 'a.jpg')).toBeLessThan(0);
  });

  it('rejects duplicate names and invalid file types', () => {
    const errors = validateScanFiles([
      jpeg('same.jpg'),
      jpeg('same.jpg'),
      new File(['png'], 'other.png', { type: 'image/png' }),
    ]);

    expect(errors).toContain('File names must be unique.');
    expect(errors).toContain('Only .jpg and .jpeg files are supported.');
  });

  it('rejects empty and oversized files', () => {
    const errors = validateScanFiles([
      jpeg('empty.jpg', ''),
      jpeg('large.jpg', 'x'.repeat(MAX_SCAN_FILE_BYTES + 1)),
    ]);

    expect(errors).toContain('Files must not be empty.');
    expect(errors).toContain('Each file must be 10 MiB or smaller.');
  });

  it('allows an empty MIME type when the filename is a JPEG', () => {
    expect(validateScanFiles([jpeg('scanner.jpg', 'jpeg', '')])).toEqual([]);
  });
});
