import { createFakeScanUploader } from './fake-scan-uploader';
import type { ScanUploadSlot } from './client';

const MAX_CONCURRENT_UPLOADS = 10;

type FetchLike = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export interface ScanUploadItem {
  slot: ScanUploadSlot;
  file: File;
}

export interface ScanUploader {
  uploadBatch(
    scanId: string,
    uploads: readonly ScanUploadItem[],
  ): Promise<void>;
}

type ValidatedScanUpload = ScanUploadItem & {
  slot: ScanUploadSlot & {
    upload_url: string;
    upload_headers: Record<string, string>;
  };
};

function validateUpload(upload: ScanUploadItem): ValidatedScanUpload {
  const { slot } = upload;
  if (!slot.upload_url) {
    throw new Error(`Missing upload URL for ${slot.filename}`);
  }
  if (!slot.upload_headers) {
    throw new Error(`Missing upload headers for ${slot.filename}`);
  }
  return {
    file: upload.file,
    slot: {
      ...slot,
      upload_url: slot.upload_url,
      upload_headers: slot.upload_headers,
    },
  };
}

async function uploadFile(
  fetchImpl: FetchLike,
  upload: ValidatedScanUpload,
): Promise<void> {
  const { slot, file } = upload;
  const response = await fetchImpl(slot.upload_url, {
    method: 'PUT',
    headers: slot.upload_headers,
    body: file,
  });

  if (!response.ok) {
    throw new Error(`Failed to upload ${slot.filename} (${response.status})`);
  }
}

export function createHttpScanUploader(
  fetchImpl: FetchLike = fetch,
): ScanUploader {
  return {
    async uploadBatch(
      _scanId: string,
      uploads: readonly ScanUploadItem[],
    ): Promise<void> {
      const validatedUploads = uploads.map(validateUpload);

      for (
        let offset = 0;
        offset < validatedUploads.length;
        offset += MAX_CONCURRENT_UPLOADS
      ) {
        const batch = validatedUploads.slice(
          offset,
          offset + MAX_CONCURRENT_UPLOADS,
        );
        await Promise.all(batch.map((upload) => uploadFile(fetchImpl, upload)));
      }
    },
  };
}

export const scanUploader: ScanUploader = import.meta.env.PROD
  ? createHttpScanUploader()
  : createFakeScanUploader();
