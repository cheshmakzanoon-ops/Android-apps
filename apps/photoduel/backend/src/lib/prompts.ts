import { readFileSync } from 'node:fs';
import { z } from 'zod';

export interface Prompt {
  id: string;
  title: string;
  brief: string;
}
export interface PromptCatalog {
  version: string;
  prompts: Prompt[];
  byId: Map<string, Prompt>;
}

export class PromptCatalogError extends Error {}

const PromptEntry = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{1,32}$/),
  title: z.string().trim().min(1).max(80),
  brief: z.string().trim().min(1).max(400),
});
const CatalogFile = z
  .object({
    catalogVersion: z.string().min(1).optional(),
    version: z.string().min(1).optional(),
    prompts: z.array(z.looseObject(PromptEntry.shape)).min(1),
  })
  .refine((c) => c.catalogVersion !== undefined || c.version !== undefined, {
    message: 'catalogVersion is required',
  });

/**
 * Loads the curated catalogue written by the engine lane (assets/source/prompts/prompts.v1.json).
 * The file is read in place, never copied into the backend. Fails with a message that names the
 * path and the problem, so a missing or malformed catalogue stops the server at startup.
 */
export function loadPromptCatalog(filePath: string): PromptCatalog {
  let raw: string;
  try {
    raw = readFileSync(filePath, 'utf8');
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code ?? 'unknown';
    throw new PromptCatalogError(
      `Prompt catalogue not found or unreadable at ${filePath} (${code}). ` +
        'It is produced by the engine lane at apps/photoduel/assets/source/prompts/prompts.v1.json; ' +
        'set PROMPTS_PATH to its location.',
    );
  }
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    throw new PromptCatalogError(`Prompt catalogue at ${filePath} is not valid JSON`);
  }
  const parsed = CatalogFile.safeParse(json);
  if (!parsed.success) {
    const issues = parsed.error.issues.map((i) => `${i.path.join('.') || '(root)'}: ${i.message}`).join('; ');
    throw new PromptCatalogError(`Prompt catalogue at ${filePath} is invalid: ${issues}`);
  }
  const prompts = parsed.data.prompts.map((p) => ({ id: p.id, title: p.title, brief: p.brief }));
  const byId = new Map<string, Prompt>();
  for (const p of prompts) {
    if (byId.has(p.id)) throw new PromptCatalogError(`Prompt catalogue at ${filePath} repeats id ${p.id}`);
    byId.set(p.id, p);
  }
  return { version: (parsed.data.catalogVersion ?? parsed.data.version) as string, prompts, byId };
}
