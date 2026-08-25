import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { encodeListingPhoto } from './encode-listing-photo';

describe('encodeListingPhoto', () => {
  const originalCreateImageBitmap = globalThis.createImageBitmap;
  const originalGetContext = HTMLCanvasElement.prototype.getContext;
  const originalToBlob = HTMLCanvasElement.prototype.toBlob;

  let canvas: HTMLCanvasElement | undefined;
  let drawImage: ReturnType<typeof vi.fn>;
  let close: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    canvas = undefined;
    drawImage = vi.fn();
    close = vi.fn();

    HTMLCanvasElement.prototype.getContext = vi.fn(function (
      this: HTMLCanvasElement,
    ) {
      canvas = this;
      return { drawImage };
    }) as unknown as typeof HTMLCanvasElement.prototype.getContext;

    HTMLCanvasElement.prototype.toBlob = vi.fn((callback, type) => {
      callback(new Blob(['jpeg'], { type: type as string }));
    });

    globalThis.createImageBitmap = vi.fn();
  });

  afterEach(() => {
    globalThis.createImageBitmap = originalCreateImageBitmap;
    HTMLCanvasElement.prototype.getContext = originalGetContext;
    HTMLCanvasElement.prototype.toBlob = originalToBlob;
  });

  function mockBitmap(width: number, height: number): void {
    vi.mocked(globalThis.createImageBitmap).mockResolvedValue({
      width,
      height,
      close,
    } as ImageBitmap);
  }

  it('downscales so the longest edge is 2000 px', async () => {
    mockBitmap(4000, 1000);
    const file = new Blob(['src']);

    const result = await encodeListingPhoto(file);

    expect(globalThis.createImageBitmap).toHaveBeenCalledWith(file, {
      imageOrientation: 'from-image',
    });
    expect(canvas?.width).toBe(2000);
    expect(canvas?.height).toBe(500);
    expect(drawImage).toHaveBeenCalledWith(
      expect.objectContaining({ width: 4000, height: 1000 }),
      0,
      0,
      2000,
      500,
    );
    expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledWith(
      expect.any(Function),
      'image/jpeg',
      0.85,
    );
    expect(result.type).toBe('image/jpeg');
    expect(close).toHaveBeenCalled();
  });

  it('does not upscale images already within the max edge', async () => {
    mockBitmap(1000, 1000);

    await encodeListingPhoto(new Blob(['src']));

    expect(canvas?.width).toBe(1000);
    expect(canvas?.height).toBe(1000);
  });

  it('fails loudly when the browser cannot decode the file', async () => {
    vi.mocked(globalThis.createImageBitmap).mockRejectedValue(
      new Error('could not decode'),
    );

    await expect(encodeListingPhoto(new Blob(['heic']))).rejects.toThrow(
      'could not decode',
    );
    expect(close).not.toHaveBeenCalled();
  });
});
