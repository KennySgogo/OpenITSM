import { before, after, afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { createRenderer, reactive, nextTick, ssrContextKey, h } from 'vue';
import { createServer } from 'vite';
import vue from '@vitejs/plugin-vue';

let server: Awaited<ReturnType<typeof createServer>>;
let component: any;
const apps: Array<{ unmount(): void }> = [];
afterEach(() => { for (const app of apps.splice(0)) app.unmount(); });
const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null });
before(async () => {
  globalThis.window = { setInterval, clearInterval } as unknown as Window & typeof globalThis;
  server = await createServer({ configFile: false, root: process.cwd(), plugins: [vue()], server: { middlewareMode: true, hmr: false, watch: null }, appType: 'custom', logLevel: 'silent' });
  component = (await server.ssrLoadModule('/src/components/AssigneeRecommendations.vue')).default;
  component.render = () => null;
  (await server.ssrLoadModule('/src/api/HttpClient.ts')).setCsrfToken('test-csrf');
});
after(async () => server.close());

test('recommendations remain advisory and stale input, manual selection and unmount discard late replies', async () => {
  for (const field of ['body', 'invalidationKey', 'disabled', 'unmount', 'unchanged']) {
    let resolve!: (value: Response) => void;
    globalThis.fetch = () => new Promise(value => { resolve = value; });
    const props = reactive({ endpoint: '/ai/assignee-recommendations', body: { title: 'Issue', description: 'Description', businessTypeId: 'type', impactScope: 'Team' }, invalidationKey: '', disabled: false });
    const app = renderer.createApp({ render: () => h(component, props) });
    apps.push(app);
    app.provide(ssrContextKey, { modules: new Set() });
    const vm = app.mount({});
    const state = vm.$.subTree.component!.setupState as any;
    const run = state.generate();
    if (field === 'body') props.body.title = 'Changed';
    if (field === 'invalidationKey') props.invalidationKey = 'manual-assignee';
    if (field === 'disabled') props.disabled = true;
    if (field === 'unmount') app.unmount();
    await nextTick();
    resolve(new Response(JSON.stringify({ data: { items: [{ assigneeId: 'candidate', name: 'Engineer', reason: 'skills' }] } }), { status: 200 }));
    await run;
    if (field === 'unchanged') assert.equal(state.items.length, 1);
    else assert.equal(state.items, undefined, field);
    assert.equal(props.invalidationKey, field === 'invalidationKey' ? 'manual-assignee' : '');
    if (field !== 'unmount') app.unmount();
  }
});
