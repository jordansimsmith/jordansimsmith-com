import { createFakeScanUploader } from './fake-scan-uploader';
import type { ScanUploadSlot } from './client';

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

export function createUnavailableScanUploader(): ScanUploader {
  return {
    async uploadBatch(
      _scanId: string,
      _uploads: readonly ScanUploadItem[],
    ): Promise<void> {
      throw new Error(
        'Direct scan uploads are not available in this build yet',
      );
    },
  };
}

export const scanUploader: ScanUploader = import.meta.env.PROD
  ? createUnavailableScanUploader()
  : createFakeScanUploader();
