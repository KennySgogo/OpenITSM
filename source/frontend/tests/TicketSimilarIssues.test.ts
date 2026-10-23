import { before, after, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { createRenderer, reactive, nextTick, ssrContextKey, h } from 'vue';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';

let server: Awaited<ReturnType<typeof createServer>>;
let component: any;
const apps: Array<{ unmount(): void }> = [];
const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null });
before(async () => {
  globalThis.window = { setInterval, clearInterval } as unknown as Window & typeof globalThis;
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [vue()], server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'silent' });
  component = (await server.ssrLoadModule('/src/components/TicketSimilarIssues.vue')).default;
  component.render = () => null;
  (await server.ssrLoadModule('/src/api/HttpClient.ts')).setCsrfToken('test-csrf');
});
afterEach(() => { for (const app of apps.splice(0)) app.unmount(); });
after(async () => server.close());
function mount() {
  const props = reactive({ ticket: { id: 'ticket', version: 1 }, refreshing: false });
  const app = renderer.createApp({ render: () => h(component, props) });
  apps.push(app); app.provide(ssrContextKey, { modules: new Set() });
  const vm = app.mount({});
  return { props, app, state: vm.$.subTree.component!.setupState as any };
}

test('navigation, ticket updates, refresh and unmount discard late source results', async () => {
  for (const change of ['id', 'version', 'refresh', 'unmount']) {
    let resolve!: (value: Response) => void;
    globalThis.fetch = (_path, init) => {
      assert.equal(init?.method, 'POST'); assert.equal(init?.body, undefined);
      return new Promise(value => { resolve = value; });
    };
    const { props, app, state } = mount();
    const run = state.search();
    if (change === 'id') props.ticket.id = 'other';
    if (change === 'version') props.ticket.version++;
    if (change === 'refresh') props.refreshing = true;
    if (change === 'unmount') app.unmount();
    await nextTick();
    resolve(new Response(JSON.stringify({ data: { items: [{ sourceType: 'KNOWLEDGE', sourceId: 'old' }] } }), { status: 200 }));
    await run;
    assert.equal(state.items, undefined);
  }
});

test('empty matches are successful while dependency failure remains an error', async () => {
  const { state } = mount();
  globalThis.fetch = async () => new Response(JSON.stringify({ data: { items: [] } }), { status: 200 });
  await state.search();
  assert.deepEqual(state.items, []); assert.equal(state.error, undefined);
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { code: 'DEPENDENCY_UNAVAILABLE', message: '服务暂不可用' }, requestId: 'test' }), { status: 503 });
  await state.search();
  assert.equal(state.items, undefined); assert.equal(state.error.code, 'DEPENDENCY_UNAVAILABLE');
});
