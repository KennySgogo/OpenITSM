export type Role = 'EMPLOYEE' | 'ENGINEER' | 'ADMIN';
export interface KnowledgeArticle {
  id: string; title: string; content: string; category: string; status: 'DRAFT' | 'PUBLISHED';
  authorId: string; authorName: string; version: number;
  createdAt: string; updatedAt: string; publishedAt: string | null;
}
export interface KnowledgeArticlePage { items: KnowledgeArticle[]; page: number; pageSize: number; total: number }
export interface SaveKnowledgeArticle { title: string; content: string; category: string }
export type MemberStatus = 'INVITED' | 'ACTIVE' | 'DISABLED';
export interface User {
  id: string;
  name: string;
  email: string;
  role: Role;
  status: MemberStatus;
  version: number;
  lockedUntil: string | null;
}
export interface Session {
  user: User;
  defaultPath: string;
  redirectPath: string;
  absoluteExpiresAt: string;
  csrfToken: string;
  message: string;
}
export interface MemberPage {
  items: User[];
  page: number;
  pageSize: number;
  total: number;
}
export interface MemberAction {
  resourceId: string;
  acceptedVersion: number;
  replayed: boolean;
}
export interface FieldError { field: string; code: string; message: string }
export interface CreateTicketRequest {
  title: string;
  description: string;
  businessType: string;
  impactScope: string;
}
export interface CreatedTicket {
  id: string;
  number: string;
  replayed: boolean;
}
export type TicketView = 'MINE' | 'ASSIGNED' | 'UNASSIGNED' | 'ALL';
export type TicketStatus = 'NEW' | 'IN_PROGRESS' | 'ON_HOLD' | 'RESOLVED' | 'CLOSED';
export interface TicketSla {
  responseDueAt: string; resolutionDueAt: string;
  firstRespondedAt: string | null; resolvedAt: string | null;
  responseOverdue: boolean; resolutionOverdue: boolean;
}
export interface Ticket {
  id: string; number: string; title: string; description: string;
  businessType: string; impactScope: string;
  requesterId: string; requesterName: string;
  assigneeId: string | null; assigneeName: string | null;
  status: TicketStatus; priority: 'P1' | 'P2' | 'P3' | 'P4'; version: number;
  createdAt: string; resolutionSummary: string | null; closedAt: string | null; cycleStartedAt: string;
  sla: TicketSla | null;
}
export interface TicketPage { items: Ticket[]; page: number; pageSize: number; total: number }
export interface TicketAction { resourceId: string; acceptedVersion: number; replayed: boolean }
export interface TicketReply {
  id: string; content: string; visibility: 'PUBLIC' | 'INTERNAL';
  authorId: string; authorName: string; createdAt: string;
}
export interface TicketReplyPage { items: TicketReply[]; page: number; pageSize: number; total: number }
export interface TicketHistoryOperation {
  id: string; action: string; actorId: string | null; actorName: string | null; createdAt: string;
  details: {
    replyId?: string;
    attachmentId?: string;
    fileName?: string;
    size?: number;
    before?: Record<string, string | null>;
    after?: Record<string, string | null>;
    reason?: string;
  };
}
export interface TicketOperationPage { items: TicketHistoryOperation[]; page: number; pageSize: number; total: number }
export interface TicketAttachment {
  id: string; fileName: string; size: number; visibility: 'PUBLIC' | 'INTERNAL';
  uploaderId: string; uploaderName: string; createdAt: string;
}
export interface TicketAttachmentPage { items: TicketAttachment[]; page: number; pageSize: number; total: number }
export interface Assignee { id: string; name: string; unresolvedCount: number; pendingConfirmationCount: number }
export const ticketViewLabels: Record<TicketView, string> = { MINE: '我的工单', ASSIGNED: '我的待办', UNASSIGNED: '未分派队列', ALL: '全部工单' };
export const ticketStatusLabels: Record<TicketStatus, string> = { NEW: '新建', IN_PROGRESS: '处理中', ON_HOLD: '已挂起', RESOLVED: '已解决', CLOSED: '已关闭' };
export const roleLabels: Record<Role, string> = { EMPLOYEE: '员工', ENGINEER: '工程师', ADMIN: '管理员' };
export const statusLabels: Record<MemberStatus, string> = { INVITED: '待激活', ACTIVE: '在职', DISABLED: '已停用' };
