import { after, afterEach, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { createRenderer, nextTick, ssrContextKey } from 'vue';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';

const typeA = '00000000-0000-0000-0000-000000000001';
const typeB = '00000000-0000-0000-0000-000000000002';
const suggestion = { businessTypeId: typeB, reason: '网络连接问题', suggestedPriority: 'P2' };
const ok = (data: unknown) => new Response(JSON.stringify({ data }), { status: 200 });
let server: Awaited<ReturnType<typeof createServer>>;
let component: any;
let csrf: (value: string) => void;
let handler: (path: string, init?: RequestInit) => Promise<Response>;
const apps: Array<{ unmount(): void }> = [];
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null
});
before(async () => {
  globalThis.window = { setInterval, clearInterval } as unknown as Window & typeof globalThis;
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [vue()],
    server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'silent' });
  component = (await server.ssrLoadModule('/src/views/CreateTicketPage.vue')).default;
  // Mount the actual SFC setup and lifecycle; Element Plus rendering belongs to browser acceptance.
  component.render = () => null;
  csrf = (await server.ssrLoadModule('/src/api/HttpClient.ts')).setCsrfToken;
});
afterEach(() => { for (const app of apps.splice(0)) app.unmount(); });
after(async () => { await server.close(); });
async function mount() {
  handler = async path => {
    if (path.includes('/ticket-types')) return ok({ items: [{ id: typeA, name: '账号' }, { id: typeB, name: '网络' }] });
    if (path.includes('/users/assignees')) return ok({ items: [] });
    throw new Error('Unexpected request: '+path);
  };
  globalThis.fetch = (input, init) => handler(String(input), init);
  csrf('test-csrf');
  const app = renderer.createApp(component);
  app.provide(ssrContextKey, { modules: new Set() });
  const vm = app.mount({});
  apps.push(app);
  await new Promise(resolve => setImmediate(resolve));
  const state = vm.$.setupState as any;
  Object.assign(state.model, { title: 'VPN 无法连接', description: '客户端连接公司内网超时', impactScope: '', businessTypeId: '' });
  state.form = { validate: async () => true, clearValidate() {} };
  return { state, app };
}
function deferred() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>(value => { resolve = value; });
  return { promise, resolve };
}

test('pre-create suggestion needs no type and only explicit adoption fills type without changing priority', async () => {
  const { state } = await mount();
  let payload: Record<string, unknown> = {};
  handler = async (path, init) => {
    assert.equal(path, '/api/v1/ai/classification-suggestions');
    payload = JSON.parse(String(init?.body));
    return ok(suggestion);
  };
  await state.generateSuggestion();
  assert.deepEqual(Object.keys(payload).sort(), ['description', 'impactScope', 'title']);
  assert.equal(state.model.businessTypeId, '');
  assert.equal(state.model.priority, 'P3');
  state.adoptSuggestion();
  assert.equal(state.model.businessTypeId, typeB);
  assert.equal(state.model.priority, 'P3');
  assert.match(state.aiNotice, /已采用/);
});

test('changing any request material or manual type discards in-flight suggestions', async () => {
  for (const field of ['title', 'description', 'impactScope', 'businessTypeId']) {
    const { state, app } = await mount();
    const pending = deferred();
    handler = async () => pending.promise;
    const run = state.generateSuggestion();
    assert.equal(state.aiBusy, true);
    state.model[field] = field === 'businessTypeId' ? typeA : '用户已经修改';
    await nextTick();
    pending.resolve(ok(suggestion));
    await run;
    assert.equal(state.suggestion, undefined, field);
    assert.equal(state.model[field], field === 'businessTypeId' ? typeA : '用户已经修改');
    app.unmount(); apps.splice(apps.indexOf(app), 1);
  }
});

test('a late older response cannot replace a newer suggestion or reset its loading state', async () => {
  const { state } = await mount();
  const first = deferred(), second = deferred(); let calls = 0;
  handler = async () => (++calls === 1 ? first.promise : second.promise);
  const old = state.generateSuggestion();
  state.model.title = '新的网络问题';
  const current = state.generateSuggestion();
  first.resolve(ok({ businessTypeId: typeA, reason: '过时结果', suggestedPriority: 'P1' }));
  await old;
  assert.equal(state.aiBusy, true);
  assert.equal(state.suggestion, undefined);
  second.resolve(ok(suggestion));
  await current;
  assert.equal(state.suggestion.businessTypeId, typeB);
  assert.equal(state.model.businessTypeId, '');
});

test('manual creation during AI wait succeeds independently and ignores the late result', async () => {
  const { state } = await mount();
  state.model.businessTypeId = typeA; state.model.impactScope = '测试账号';
  const pending = deferred(); let creations = 0;
  handler = async path => {
    if (path.endsWith('/ai/classification-suggestions')) return pending.promise;
    assert.equal(path, '/api/v1/tickets'); creations++;
    return ok({ id: 'created-ticket', number: 'ITSM-TEST' });
  };
  const run = state.generateSuggestion();
  await state.submit();
  pending.resolve(ok(suggestion)); await run;
  assert.equal(state.created.id, 'created-ticket');
  assert.equal(creations, 1);
  assert.equal(state.suggestion, undefined);
  assert.equal(state.model.businessTypeId, typeA);
});

test('leaving the page discards a response arriving after unmount', async () => {
  const { state, app } = await mount(); const pending = deferred();
  handler = async () => pending.promise;
  const run = state.generateSuggestion();
  app.unmount(); apps.splice(apps.indexOf(app), 1);
  pending.resolve(ok(suggestion)); await run;
  assert.equal(state.suggestion, undefined);
});

test('AI disabled keeps the draft and manual submission usable', async () => {
  const { state } = await mount(); state.model.businessTypeId = typeA;
  handler = async path => path.endsWith('/ai/classification-suggestions')
    ? new Response(JSON.stringify({ error: { code: 'AI_DISABLED', message: 'AI 已关闭' } }), { status: 503 })
    : ok({ id: 'manual-ticket', number: 'ITSM-MANUAL' });
  const title = state.model.title;
  await state.generateSuggestion();
  assert.equal(state.aiError.code, 'AI_DISABLED');
  assert.equal(state.error, undefined);
  assert.equal(state.model.title, title);
  await state.submit();
  assert.equal(state.created.id, 'manual-ticket');
});

test('type retired after adoption is rejected on submit, options refresh without selecting another type', async () => {
  const { state } = await mount(); let retired = false;
  handler = async path => {
    if (path.endsWith('/ai/classification-suggestions')) return ok(suggestion);
    if (path === '/api/v1/tickets') {
      retired = true;
      return new Response(JSON.stringify({ error: { code: 'VALIDATION_FAILED', message: '类型已失效', fieldErrors: [{ field: 'businessTypeId', message: '请选择有效类型' }] } }), { status: 400 });
    }
    assert.equal(retired, true);
    return ok({ items: [{ id: typeA, name: '账号' }] });
  };
  await state.generateSuggestion(); state.adoptSuggestion();
  assert.equal(state.model.businessTypeId, typeB);
  await state.submit();
  assert.equal(state.created, undefined);
  assert.equal(state.error.code, 'VALIDATION_FAILED');
  assert.equal(state.model.businessTypeId, '');
  assert.match(state.typeNotice, /重新选择/);
});
