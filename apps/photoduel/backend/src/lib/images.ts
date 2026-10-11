import sharp from 'sharp';
import { ApiError } from './errors.js';
import { sha256Hex } from './crypto.js';

sharp.cache(false);

export type DetectedType = 'jpeg' | 'png' | 'webp';

/** Detects the container by magic bytes only. File names and Content-Type headers are ignored. */
export function detectImageType(buf: Buffer): DetectedType | null {
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpeg';
  if (
    buf.length >= 8 &&
    buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47 &&
    buf[4] === 0x0d && buf[5] === 0x0a && buf[6] === 0x1a && buf[7] === 0x0a
  ) {
    return 'png';
  }
  if (
    buf.length >= 12 &&
    buf.toString('latin1', 0, 4) === 'RIFF' &&
    buf.toString('latin1', 8, 12) === 'WEBP'
  ) {
    return 'webp';
  }
  return null;
}

export interface EncodedImage {
  bytes: Buffer;
  width: number;
  height: number;
  sha256: string;
}

export interface ImageLimits {
  maxInputPixels: number;
  minShortEdgePx: number;
}

const unsupported = (message: string) => new ApiError('UNSUPPORTED_MEDIA', message);

/** Validates type, pixel count and short edge without decoding pixel data. */
async function inspect(buf: Buffer, limits: ImageLimits, enforceShortEdge: boolean, label: string): Promise<void> {
  const type = detectImageType(buf);
  if (!type) throw unsupported(`${label} must be a JPEG, PNG or WebP image`);
  let meta: Awaited<ReturnType<ReturnType<typeof sharp>['metadata']>>;
  try {
    meta = await sharp(buf, { limitInputPixels: false, failOn: 'error' }).metadata();
  } catch {
    throw unsupported(`${label} could not be read as an image`);
  }
  // The decoder must agree with the magic bytes, so a polyglot cannot pick a different codec.
  if (meta.format !== type) throw unsupported(`${label} must be a JPEG, PNG or WebP image`);
  const w = meta.width ?? 0;
  const h = meta.height ?? 0;
  if (w <= 0 || h <= 0) throw unsupported(`${label} has no dimensions`);
  if ((meta.pages ?? 1) > 1) throw unsupported(`${label} must be a single still image`);
  if (w * h > limits.maxInputPixels) throw unsupported(`${label} exceeds the pixel limit`);
  if (enforceShortEdge && Math.min(w, h) < limits.minShortEdgePx) {
    throw unsupported(`${label} is too small: the short edge must be at least ${limits.minShortEdgePx} px`);
  }
}

async function encode(input: Buffer, longEdge: number, quality: number, limits: ImageLimits): Promise<EncodedImage> {
  try {
    // rotate() applies the EXIF orientation. sharp writes no metadata unless asked (no
    // withMetadata/keepExif here), so EXIF, GPS, XMP, IPTC and ICC comments are all dropped.
    const { data, info } = await sharp(input, { limitInputPixels: limits.maxInputPixels, failOn: 'error' })
      .rotate()
      .resize({ width: longEdge, height: longEdge, fit: 'inside', withoutEnlargement: true })
      .flatten({ background: '#ffffff' })
      .jpeg({ quality, chromaSubsampling: '4:2:0', progressive: false })
      .toBuffer({ resolveWithObject: true });
    return { bytes: data, width: info.width, height: info.height, sha256: sha256Hex(data) };
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw unsupported('Image could not be decoded');
  }
}

export interface NormalizedPhoto {
  full: EncodedImage;
  thumbnail: EncodedImage;
}

/**
 * Validates both uploaded parts, then re-encodes. The stored thumbnail is derived from the
 * normalised full image (480 px long edge, quality 80) rather than from the client thumbnail, so
 * the two variants always show the same photo even when a hostile client sends mismatched parts.
 * The client thumbnail is still validated (type, size, pixel cap) and then discarded.
 */
export async function normalizePhoto(full: Buffer, thumbnail: Buffer, limits: ImageLimits): Promise<NormalizedPhoto> {
  await inspect(full, limits, true, 'full');
  await inspect(thumbnail, limits, false, 'thumbnail');
  const fullOut = await encode(full, 1600, 82, limits);
  const thumbOut = await encode(fullOut.bytes, 480, 80, limits);
  return { full: fullOut, thumbnail: thumbOut };
}
