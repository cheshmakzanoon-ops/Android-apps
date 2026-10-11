/**
 * The only time source for rules. Production uses the server's Date.now(); tests inject a manual
 * clock to move deadlines forward without sleeping. Client-supplied times are never used.
 */
export interface Clock {
  now(): number;
}

export const systemClock: Clock = { now: () => Date.now() };

export class ManualClock implements Clock {
  private current: number;
  constructor(start: number = Date.UTC(2026, 9, 10, 12, 0, 0)) {
    this.current = start;
  }
  now(): number {
    return this.current;
  }
  set(epochMillis: number): void {
    this.current = epochMillis;
  }
  advance(ms: number): void {
    this.current += ms;
  }
}

export const HOUR_MS = 3600_000;
export const DAY_MS = 24 * HOUR_MS;
