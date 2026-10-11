import { randomBytes } from 'node:crypto';
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * Private object storage for photo bytes. Nothing here is publicly addressable; the API streams
 * bytes only after a per-request authorization check. The production target is an S3-compatible
 * private bucket (not provisioned in this build); LocalDiskStore is the implementation used here.
 */
export interface ObjectStore {
  /** Returns a fresh, unguessable, sharded key. */
  newKey(): string;
  put(key: string, data: Buffer): Promise<void>;
  get(key: string): Promise<Buffer | null>;
  delete(key: string): Promise<void>;
  /** Every stored key with its last-modified time, for the orphan sweeper. */
  list(): AsyncIterable<{ key: string; modifiedAt: number }>;
}

const KEY_RE = /^[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f]{32}\.jpg$/;

export class LocalDiskStore implements ObjectStore {
  constructor(private readonly root: string) {}

  newKey(): string {
    const h = randomBytes(16).toString('hex');
    return `${h.slice(0, 2)}/${h.slice(2, 4)}/${h}.jpg`;
  }

  private resolve(key: string): string {
    if (!KEY_RE.test(key)) throw new Error('Invalid object key');
    return path.join(this.root, ...key.split('/'));
  }

  async put(key: string, data: Buffer): Promise<void> {
    const target = this.resolve(key);
    await mkdir(path.dirname(target), { recursive: true, mode: 0o700 });
    const tmp = `${target}.${randomBytes(6).toString('hex')}.tmp`;
    await writeFile(tmp, data, { mode: 0o600, flag: 'wx' });
    await rename(tmp, target);
  }

  async get(key: string): Promise<Buffer | null> {
    try {
      return await readFile(this.resolve(key));
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null;
      throw err;
    }
  }

  async delete(key: string): Promise<void> {
    await rm(this.resolve(key), { force: true });
  }

  async *list(): AsyncIterable<{ key: string; modifiedAt: number }> {
    let shards1: string[];
    try {
      shards1 = await readdir(this.root);
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') return;
      throw err;
    }
    for (const a of shards1) {
      if (!/^[0-9a-f]{2}$/.test(a)) continue;
      for (const b of await readdir(path.join(this.root, a)).catch(() => [] as string[])) {
        if (!/^[0-9a-f]{2}$/.test(b)) continue;
        for (const f of await readdir(path.join(this.root, a, b)).catch(() => [] as string[])) {
          const key = `${a}/${b}/${f}`;
          const full = path.join(this.root, a, b, f);
          const st = await stat(full).catch(() => null);
          if (!st) continue;
          if (KEY_RE.test(key)) {
            yield { key, modifiedAt: st.mtimeMs };
          } else if (f.endsWith('.tmp') && Date.now() - st.mtimeMs > 3600_000) {
            await rm(full, { force: true }); // abandoned partial write
          }
        }
      }
    }
  }
}
