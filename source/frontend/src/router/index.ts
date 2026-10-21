import { createRouter, createWebHistory } from 'vue-router';
import AuthPage from '../views/AuthPage.vue';
import ApplicationLayout from '../components/ApplicationLayout.vue';
import MembersPage from '../views/MembersPage.vue';
import CreateTicketPage from '../views/CreateTicketPage.vue';
import TicketsPage from '../views/TicketsPage.vue';
import TicketDetailPage from '../views/TicketDetailPage.vue';
import KnowledgePage from '../views/KnowledgePage.vue';
import KnowledgeArticlePage from '../views/KnowledgeArticlePage.vue';
import PlaceholderPage from '../views/PlaceholderPage.vue';
import ProfilePage from '../views/ProfilePage.vue';
import TicketTypePage from '../views/TicketTypePage.vue';
import DashboardPage from '../views/DashboardPage.vue';
import SettingsPage from '../views/SettingsPage.vue';
import { sessionState, sessionNotice, restoreSession, clearSession } from '../stores/SessionStore';
import { setExpiredHandler, ApiError } from '../api/HttpClient';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...['login', 'activate', 'forgot-password', 'reset-password'].map(path => ({ path: `/${path}`, component: AuthPage, meta: { public: true } })),
    {
      path: '/', component: ApplicationLayout, children: [
        { path: '', component: PlaceholderPage, meta: { title: '工作台' } },
        { path: 'profile', component: ProfilePage, meta: { title: '个人资料' } },
        { path: 'admin/users', component: MembersPage, meta: { title: '成员管理' } },
        { path: 'tickets', component: TicketsPage, meta: { title: '工单' } },
        { path: 'tickets/new', component: CreateTicketPage, meta: { title: '提交工单' } },
        { path: 'tickets/:id', component: TicketDetailPage, meta: { title: '工单详情' } },
        { path: 'dashboard', component: DashboardPage, meta: { title: '服务看板' } },
        { path: 'ticket-types', component: TicketTypePage, meta: { title: '业务类型' } },
        { path: 'knowledge', component: KnowledgePage, meta: { title: '知识库' } },
        { path: 'knowledge/new', component: KnowledgeArticlePage, meta: { title: '新建知识草稿' } },
        { path: 'knowledge/:id', component: KnowledgeArticlePage, meta: { title: '知识详情' } },
        { path: 'settings', component: SettingsPage, meta: { title: '团队配置' } },
        { path: ':pathMatch(.*)*', component: PlaceholderPage, meta: { title: '页面未找到', missing: true } }
      ]
    }
  ],
  scrollBehavior: () => ({ top: 0 })
});
setExpiredHandler(failure => {
  clearSession();
  sessionNotice.value = failure;
  const route = router.currentRoute.value;
  if (!route.meta.public && route.path !== '/login') void router.replace({ path: '/login', query: { returnTo: route.fullPath } });
});
router.beforeEach(async to => {
  if (to.meta.public) return true;
  if (!sessionState.restored) {
    try { await restoreSession(); }
    catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) return true;
    }
  }
  if (sessionState.restored && !sessionState.session) return { path: '/login', query: { returnTo: to.fullPath } };
  if (to.path === '/' && sessionState.session) return sessionState.session.defaultPath;
  return true;
});
