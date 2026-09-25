import { describe, expect, it, vi } from 'vitest';
import type { ScanUploadSlot } from './client';
import { createHttpScanUploader, type ScanUploadItem } from './scan-uploader';

function uploadItem(
  filename: string,
  overrides: Partial<ScanUploadSlot> = {},
): ScanUploadItem {
  return {
    slot: {
      scan_position: Number.parseInt(filename, 10) || 1,
      filename,
      size_bytes: 4,
      uploaded: false,
      upload_url: `https://s3.example/${filename}`,
      upload_headers: {
        'Content-Type': 'image/jpeg',
        'If-None-Match': '*',
      },
      ...overrides,
    },
    file: new File(['jpeg'], filename, { type: 'image/jpeg' }),
  };
}

function okResponse(): Response {
  return new Response(null, { status: 200 });
}

describe('http scan uploader', () => {
  it('puts the original file with the signed headers and no authorization', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(okResponse());
    const item = uploadItem('001.jpg');

    await createHttpScanUploader(fetchImpl).uploadBatch('scan-1', [item]);

    expect(fetchImpl).toHaveBeenCalledWith(item.slot.upload_url, {
      method: 'PUT',
      headers: item.slot.upload_headers,
      body: item.file,
    });
    const request = fetchImpl.mock.calls[0]?.[1];
    expect(request?.headers).not.toHaveProperty('Authorization');
  });

  it('uploads batches with at most ten concurrent requests', async () => {
    let active = 0;
    let maximumActive = 0;
    const started: string[] = [];
    const fetchImpl = vi.fn<typeof fetch>(async (input) => {
      started.push(String(input));
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await Promise.resolve();
      active -= 1;
      return okResponse();
    });
    const uploads = Array.from({ length: 21 }, (_, index) =>
      uploadItem(`${String(index + 1).padStart(3, '0')}.jpg`),
    );

    await createHttpScanUploader(fetchImpl).uploadBatch('scan-1', uploads);

    expect(fetchImpl).toHaveBeenCalledTimes(21);
    expect(maximumActive).toBe(10);
    expect(started.slice(0, 10)).toEqual(
      uploads.slice(0, 10).map((upload) => upload.slot.upload_url),
    );
    expect(started.slice(10)).toEqual(
      uploads.slice(10).map((upload) => upload.slot.upload_url),
    );
  });

  it('stops before later batches when an upload returns a non-success response', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) => {
      const status = String(input).endsWith('001.jpg') ? 503 : 200;
      return new Response(null, { status });
    });
    const uploads = Array.from({ length: 11 }, (_, index) =>
      uploadItem(`${String(index + 1).padStart(3, '0')}.jpg`),
    );

    await expect(
      createHttpScanUploader(fetchImpl).uploadBatch('scan-1', uploads),
    ).rejects.toThrow('Failed to upload 001.jpg (503)');
    expect(fetchImpl).toHaveBeenCalledTimes(10);
  });

  it('propagates network failures without retrying', async () => {
    const failure = new TypeError('network interrupted');
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(failure);

    await expect(
      createHttpScanUploader(fetchImpl).uploadBatch('scan-1', [
        uploadItem('001.jpg'),
      ]),
    ).rejects.toBe(failure);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('rejects missing upload URLs before sending any request', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(okResponse());
    const uploads = [
      uploadItem('001.jpg', { upload_url: null }),
      uploadItem('002.jpg'),
    ];

    await expect(
      createHttpScanUploader(fetchImpl).uploadBatch('scan-1', uploads),
    ).rejects.toThrow('Missing upload URL for 001.jpg');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('rejects missing upload headers before sending any request', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(okResponse());

    await expect(
      createHttpScanUploader(fetchImpl).uploadBatch('scan-1', [
        uploadItem('001.jpg', { upload_headers: null }),
      ]),
    ).rejects.toThrow('Missing upload headers for 001.jpg');
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
