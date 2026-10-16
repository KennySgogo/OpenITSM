import { ticketStatusLabels } from '../types/ApiTypes.ts';
import type { Ticket, User, TicketHistoryOperation } from '../types/ApiTypes.ts';

export function canReply(ticket: Ticket, user: Pick<User, 'id' | 'role'>, internal = false): boolean {
  if (ticket.status === 'CLOSED') return false;
  if (user.role === 'ENGINEER' || user.role === 'ADMIN') return true;
  return !internal && ticket.requesterId === user.id;
}

const labels: Record<string, string> = {
  TICKET_CLAIM: '认领 / 受理', TICKET_ASSIGNMENTS: '分派', TICKET_TRANSFERS: '转派',
  TICKET_SUSPENSIONS: '挂起', TICKET_RESUME: '恢复', TICKET_RESOLUTIONS: '解决',
  TICKET_CLOSE: '关闭', TICKET_REOPEN: '重开', TICKET_PRIORITY_CHANGES: '调整优先级',
  TICKET_REPLY: '发表公开回复', TICKET_INTERNAL_NOTE: '添加内部备注',
  TICKET_ATTACHMENT: '上传公开附件', TICKET_INTERNAL_ATTACHMENT: '上传内部附件'
};
export function historyActionLabel(action: string): string { return labels[action] ?? '工单操作'; }

export function historyChanges(event: TicketHistoryOperation): string[] {
  const before = event.details.before;
  const after = event.details.after;
  if (!before || !after) return [];
  const changes: string[] = [];
  if (before.status !== after.status && after.status) {
    const label = (status: string | null | undefined) => ticketStatusLabels[status as Ticket['status']] ?? status ?? '—';
    changes.push(`状态：${label(before.status)} → ${label(after.status)}`);
  }
  if (before.priority !== after.priority && after.priority) changes.push(`优先级：${before.priority ?? '—'} → ${after.priority}`);
  if (before.assigneeId !== after.assigneeId) changes.push(after.assigneeId ? '已更新负责人' : '已清空负责人');
  if (before.summary !== after.summary && after.summary) changes.push(`解决摘要：${after.summary}`);
  return changes;
}
