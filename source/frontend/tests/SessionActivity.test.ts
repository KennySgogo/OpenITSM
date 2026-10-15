import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate } from 'node:timers/promises';
import { acceptSession, clearSession, installActivityTracking, stopHeartbeat } from '../src/stores/SessionStore.ts';

test('me recovery never creates activity; trusted input does, hidden page stops and resumes safely', async context => {
  context.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1000000 });
  const handlers = new Map<string, (event: { isTrusted: boolean }) => void>();
  let visibility: () => void = () => {};
  const documentStub = { hidden: false, addEventListener: (_name: string, callback: () => void) => { visibility = callback; } };
  Object.assign(globalThis, {
    window: { addEventListener: (name: string, callback: (event: { isTrusted: boolean }) => void) => handlers.set(name, callback) },
    document: documentStub
  });
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    if (calls === 1) context.mock.timers.setTime(Date.now() + 5000);
    return new Response(null, { status: 204 });
  };
  const session = { user: { id: 'admin', name: 'Admin', email: 'admin@example.com', role: 'ADMIN' as const, status: 'ACTIVE' as const, version: 1, lockedUntil: null }, csrfToken: 'token', defaultPath: '/dashboard', redirectPath: '/dashboard', message: '登录成功', absoluteExpiresAt: '2026-10-16T00:00:00Z' };
  installActivityTracking();
  acceptSession(session);
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 0, 'restoration is not actual user activity');
  handlers.get('pointerdown')!({ isTrusted: false });
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 0, 'script-generated events do not count');
  handlers.get('keydown')!({ isTrusted: true });
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 1);
  handlers.get('keydown')!({ isTrusted: true });
  context.mock.timers.tick(55000);
  await setImmediate();
  assert.equal(calls, 2, 'network time does not push the next heartbeat beyond 60 seconds');
  documentStub.hidden = true;
  visibility();
  handlers.get('pointerdown')!({ isTrusted: true });
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 2, 'hidden pages do not report activity');
  documentStub.hidden = false;
  visibility();
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 3);
  clearSession();
  context.mock.timers.tick(60000);
  await setImmediate();
  assert.equal(calls, 3, 'clearing session cancels heartbeat');
  stopHeartbeat();
});
