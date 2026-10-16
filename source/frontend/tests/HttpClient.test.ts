import { test } from 'node:test';
import assert from 'node:assert/strict';
import { request, setCsrfToken, setExpiredHandler, clearHttpSession, ApiError, getCooldownUntil } from '../src/api/HttpClient.ts';

const ok = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 });
const fail = (status: number, code: string, headers = {}) => new Response(JSON.stringify({ error: { code, message: '后端原始文案', fieldErrors: [{ field: 'name', code: 'INVALID', message: '后端字段文案' }], retryable: status === 429 || status === 503 }, requestId: 'test-request' }), { status, headers });

test('CSRF refresh retries the exact mutation once, preserving version, key and body', async () => {
  setCsrfToken('old-token');
  const calls: { path: string; init?: RequestInit }[] = [];
  globalThis.fetch = async (input, init) => {
    calls.push({ path: String(input), init });
    if (String(input).endsWith('/auth/csrf')) return ok({ csrfToken: 'new-token' });
    return calls.length === 1 ? fail(403, 'CSRF_INVALID') : ok({ replayed: true });
  };
  const result = await request<{ replayed: boolean }>('/users/member', { method: 'PATCH', version: 3, key: 'same-key', body: { name: '张三' } });
  assert.equal(result.replayed, true);
  assert.equal(calls.length, 3);
  assert.equal(calls[0].init?.credentials, 'include');
  assert.equal((calls[2].init?.headers as Record<string, string>)['If-Match'], '"3"');
  assert.equal((calls[2].init?.headers as Record<string, string>)['Idempotency-Key'], 'same-key');
  assert.equal((calls[2].init?.headers as Record<string, string>)['X-CSRF-Token'], 'new-token');
  assert.equal(calls[0].init?.body, calls[2].init?.body);
});
test('version conflicts retain server message and never retry the mutation', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls++; return fail(409, 'VERSION_CONFLICT'); };
  await assert.rejects(request('/users/member', { method: 'PATCH', key: 'key', version: 1, body: { name: '张三' } }), (error: ApiError) => error.message === '后端原始文案' && error.fieldErrors[0].message === '后端字段文案');
  assert.equal(calls, 1);
});
test('expired sessions dispatch once but invalid credentials stay on the login form', async () => {
  let expirations = 0;
  setExpiredHandler(() => { expirations++; });
  globalThis.fetch = async () => fail(401, 'SESSION_EXPIRED');
  await assert.rejects(request('/auth/me'));
  assert.equal(expirations, 1);
  globalThis.fetch = async () => fail(401, 'AUTH_INVALID');
  await assert.rejects(request('/auth/login', { method: 'POST', body: { email: 'x@example.com', password: 'bad' } }));
  assert.equal(expirations, 1);
});
test('bodyless actions keep no body and rate limiting uses Retry-After', async () => {
  globalThis.fetch = async (_input, init) => {
    assert.equal(init?.body, undefined);
    assert.equal((init?.headers as Record<string, string>)['Content-Type'], undefined);
    return fail(429, 'RATE_LIMITED', { 'Retry-After': '120' });
  };
  await assert.rejects(request('/users/member/unlock', { method: 'POST', key: 'key', version: 1 }), (error: ApiError) => error.retryAfter === 120);
  assert.ok(getCooldownUntil() > Date.now() + 119000);
});
test('refresh after memory loss obtains CSRF before writing', async () => {
  clearHttpSession();
  const paths: string[] = [];
  globalThis.fetch = async input => {
    paths.push(String(input));
    return String(input).endsWith('/csrf') ? ok({ csrfToken: 'restored-token' }) : new Response(null, { status: 204 });
  };
  await request('/auth/logout', { method: 'POST' });
  assert.deepEqual(paths, ['/api/v1/auth/csrf', '/api/v1/auth/logout']);
});

test('multipart CSRF retry preserves file bytes, visibility, version and key and leaves boundary to browser', async () => {
  setCsrfToken('old-token');
  const body = new FormData();
  body.append('file', new Blob([new Uint8Array([0, 255, 2])]), '日志.bin');
  body.append('visibility', 'INTERNAL');
  const calls: RequestInit[] = [];
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith('/auth/csrf')) return ok({ csrfToken: 'new-token' });
    calls.push(init!);
    return calls.length === 1 ? fail(403, 'CSRF_INVALID') : ok({ replayed: true });
  };
  assert.deepEqual(await request('/tickets/id/attachments', { method: 'POST', body, key: 'same-key', version: 7 }), { replayed: true });
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.body, body);
    assert.equal((call.headers as Record<string, string>)['Content-Type'], undefined);
    assert.equal((call.headers as Record<string, string>)['Idempotency-Key'], 'same-key');
    assert.equal((call.headers as Record<string, string>)['If-Match'], '"7"');
    assert.equal(call.credentials, 'include');
  }
  assert.equal(body.get('visibility'), 'INTERNAL');
  assert.deepEqual(new Uint8Array(await (body.get('file') as Blob).arrayBuffer()), new Uint8Array([0, 255, 2]));
});

test('binary download returns bytes while authorization failures still use shared error and expiry handling', async () => {
  globalThis.fetch = async () => new Response(new Uint8Array([0, 255, 1]), { headers: { 'Content-Type': 'application/octet-stream' } });
  const blob = await request<Blob>('/attachments/id', { binary: true });
  assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), new Uint8Array([0, 255, 1]));
  globalThis.fetch = async () => fail(404, 'RESOURCE_NOT_FOUND');
  await assert.rejects(request('/attachments/id', { binary: true }), (error: ApiError) => error.status === 404 && error.code === 'RESOURCE_NOT_FOUND');
  let expired = false;
  setExpiredHandler(() => { expired = true; });
  globalThis.fetch = async () => fail(401, 'SESSION_EXPIRED');
  await assert.rejects(request('/attachments/id', { binary: true }));
  assert.equal(expired, true);
});
