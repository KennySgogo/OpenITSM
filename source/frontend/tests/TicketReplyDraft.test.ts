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
  component = (await server.ssrLoadModule('/src/components/TicketReplyDraft.vue')).default;
  component.render = () => null;
  (await server.ssrLoadModule('/src/api/HttpClient.ts')).setCsrfToken('test-csrf');
});
afterEach(() => { for (const app of apps.splice(0)) app.unmount(); });
after(async () => server.close());
function mount() {
  const props = reactive({ ticket: { id: 'ticket', version: 1 }, disabled: false, hasReplyText: false });
  const events: unknown[][] = [];
  const app = renderer.createApp({ render: () => h(component, { ...props, onAdopt: (...args: unknown[]) => events.push(args) }) });
  apps.push(app); app.provide(ssrContextKey, { modules: new Set() });
  const vm = app.mount({});
  return { props, app, events, state: vm.$.subTree.component!.setupState as any };
}
test('draft must be adopted manually and existing reply is preserved', async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ data: { content: 'draft', basedOnVersion: 1, hasReferenceSources: false, sources: [] } }), { status: 200 });
  const { state, props, events } = mount();
  await state.generate(); assert.equal(events.length, 0);
  props.hasReplyText = true; await nextTick(); state.adopt(); assert.equal(events.length, 0);
  props.hasReplyText = false; await nextTick(); state.adopt(); assert.deepEqual(events, [['draft', 1]]);
  props.ticket.version++; await nextTick(); state.adopt(); assert.equal(events.length, 1);
});
test('late draft responses are ignored after refresh, ticket change or unmount', async () => {
  for (const change of ['id', 'version', 'disabled', 'unmount']) {
    let resolve!: (response: Response) => void;
    globalThis.fetch = () => new Promise(value => { resolve = value; });
    const { props, app, state } = mount(); const pending = state.generate();
    if (change === 'id') props.ticket.id = 'other';
    if (change === 'version') props.ticket.version++;
    if (change === 'disabled') props.disabled = true;
    if (change === 'unmount') app.unmount();
    await nextTick(); resolve(new Response(JSON.stringify({ data: { content: 'old', basedOnVersion: 1, sources: [] } }), { status: 200 }));
    await pending; assert.equal(state.draft, undefined);
  }
});

test('failed regeneration preserves the previous valid draft and existing reply', async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ data: { content: 'draft', basedOnVersion: 1, hasReferenceSources: false, sources: [] } }), { status: 200 });
  const { state, props } = mount(); await state.generate();
  props.hasReplyText = true; await nextTick();
  globalThis.fetch = async () => new Response(JSON.stringify({ error: { code: 'DEPENDENCY_UNAVAILABLE', message: '服务暂不可用' } }), { status: 503 });
  await state.generate();
  assert.equal(state.draft.content, 'draft'); assert.equal(state.stale, false);
  assert.equal(state.error.code, 'DEPENDENCY_UNAVAILABLE'); assert.equal(props.hasReplyText, true);
});
