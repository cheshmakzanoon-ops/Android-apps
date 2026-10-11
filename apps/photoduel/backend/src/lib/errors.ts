/** Error codes and HTTP statuses from docs/api-contract.md section 3. */
export const ERROR_STATUS = {
  VALIDATION: 400,
  UNAUTHENTICATED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  INVITE_UNAVAILABLE: 404,
  CONFLICT: 409,
  INVALID_TRANSITION: 409,
  ALREADY_VOTED: 409,
  ROSTER_FROZEN: 409,
  ROOM_FULL: 409,
  DEADLINE_PASSED: 410,
  PAYLOAD_TOO_LARGE: 413,
  UNSUPPORTED_MEDIA: 415,
  INVALID_BALLOT: 422,
  RATE_LIMITED: 429,
  SERVER: 500,
} as const;

export type ErrorCode = keyof typeof ERROR_STATUS;

export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly retryAfterSeconds: number | undefined;

  constructor(code: ErrorCode, message: string, opts: { status?: number; retryAfterSeconds?: number } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = opts.status ?? ERROR_STATUS[code];
    this.retryAfterSeconds = opts.retryAfterSeconds;
  }

  body(): { error: { code: ErrorCode; message: string; retryAfterSeconds?: number } } {
    const error: { code: ErrorCode; message: string; retryAfterSeconds?: number } = {
      code: this.code,
      message: this.message,
    };
    if (this.retryAfterSeconds !== undefined) error.retryAfterSeconds = this.retryAfterSeconds;
    return { error };
  }
}

export const notFound = (what = 'Not found') => new ApiError('NOT_FOUND', what);
export const forbidden = (what: string) => new ApiError('FORBIDDEN', what);
export const conflict = (what: string) => new ApiError('CONFLICT', what);
export const invalidTransition = (what: string) => new ApiError('INVALID_TRANSITION', what);
export const validation = (what: string, status?: number) =>
  new ApiError('VALIDATION', what, status === undefined ? {} : { status });
export const unauthenticated = (what = 'Authentication required') => new ApiError('UNAUTHENTICATED', what);
