import type { FieldError } from '../types/ApiTypes';

export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors: FieldError[];
  requestId: string;
  retryAfter: number;
  retryable: boolean;
  constructor(status: number, error: { code: string; message: string; fieldErrors?: FieldError[]; retryable?: boolean }, requestId = '', retryAfter = 0) {
    super(error.message);
    this.status = status;
    this.code = error.code;
    this.fieldErrors = error.fieldErrors || [];
    this.requestId = requestId;
    this.retryAfter = retryAfter;
    this.retryable = error.retryable || false;
  }
}
export interface RequestOptions {
  method?: string;
  body?: unknown;
  version?: number;
  key?: string;
}
let csrfToken = '';
let csrfRequest: Promise<void> | undefined;
let expired: (failure: ApiError) => void = () => {};
let cooldownUntil = 0;
export function setExpiredHandler(handler: (failure: ApiError) => void) { expired = handler; }
export function setCsrfToken(token: string) { csrfToken = token; }
export function clearHttpSession() { csrfToken = ''; }
export function getCooldownUntil() { return cooldownUntil; }
export async function refreshCsrf(): Promise<void> {
  if (!csrfRequest) {
    csrfRequest = request<{ csrfToken: string }>('/auth/csrf').then(value => { csrfToken = value.csrfToken; }).finally(() => { csrfRequest = undefined; });
  }
  return csrfRequest;
}
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export async function request<T>(path: string, options: RequestOptions = {}, csrfRetry = false, busyRetries = 0): Promise<T> {
  const method = options.method || 'GET';
  const writing = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (writing && !csrfToken) await refreshCsrf();
  const headers: Record<string, string> = {};
  if (writing) headers['X-CSRF-Token'] = csrfToken;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.version !== undefined) headers['If-Match'] = `"${options.version}"`;
  if (options.key) headers['Idempotency-Key'] = options.key;
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method, credentials: 'include', headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: AbortSignal.timeout(20000)
    });
  } catch {
    throw new ApiError(0, { code: 'NETWORK_ERROR', message: '无法连接服务，请检查网络后重试', retryable: true });
  }
  if (response.status === 204) return undefined as T;
  let envelope;
  try { envelope = await response.json(); }
  catch { throw new ApiError(response.status, { code: 'INVALID_RESPONSE', message: '服务响应无法解析，请稍后重试' }); }
  if (response.ok) return envelope.data as T;
  const failure = new ApiError(response.status, envelope.error || { code: 'INVALID_RESPONSE', message: '服务响应无法解析，请稍后重试' }, envelope.requestId || '', Number(response.headers.get('Retry-After')) || 60);
  if (failure.code === 'CSRF_INVALID') {
    if (!csrfRetry) {
      await refreshCsrf();
      return request<T>(path, options, true, busyRetries);
    }
    window.location.reload();
  }
  if (failure.code === 'REQUEST_IN_PROGRESS' && busyRetries < 2) {
    await sleep(1000);
    return request<T>(path, options, csrfRetry, busyRetries + 1);
  }
  if (response.status === 429 && path !== '/auth/activity') cooldownUntil = Math.max(cooldownUntil, Date.now() + failure.retryAfter * 1000);
  if (response.status === 401 && failure.code !== 'AUTH_INVALID') expired(failure);
  throw failure;
}
