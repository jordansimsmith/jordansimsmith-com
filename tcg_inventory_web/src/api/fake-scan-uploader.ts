import type { ScanUploadItem, ScanUploader } from './scan-uploader';

const FAKE_SCAN_UPLOAD_DELAY_MS = 750;

export function createFakeScanUploader(): ScanUploader {
  return {
    async uploadBatch(
      _scanId: string,
      _uploads: readonly ScanUploadItem[],
    ): Promise<void> {
      await new Promise<void>((resolve) => {
        setTimeout(resolve, FAKE_SCAN_UPLOAD_DELAY_MS);
      });
    },
  };
}
