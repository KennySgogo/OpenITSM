import type { Ticket, User } from '../types/ApiTypes.ts';

export type TicketOperation = 'claim' | 'assignments' | 'transfers' | 'suspensions' | 'resume' | 'resolutions' | 'close' | 'reopen' | 'priority-changes';
export const ticketOperationLabels: Record<TicketOperation, string> = {
  claim: '认领 / 受理', assignments: '分派', transfers: '转派', suspensions: '挂起',
  resume: '恢复', resolutions: '解决', close: '关闭', reopen: '重开', 'priority-changes': '调整优先级'
};

export function ticketOperations(ticket: Ticket, user: Pick<User, 'id' | 'role'>, now = Date.now()): TicketOperation[] {
  const admin = user.role === 'ADMIN';
  const engineer = user.role === 'ENGINEER';
  const owner = engineer && ticket.assigneeId === user.id;
  const requester = ticket.requesterId === user.id;
  const active = ['NEW', 'IN_PROGRESS', 'ON_HOLD'].includes(ticket.status);
  const actions: TicketOperation[] = [];
  if (engineer && ticket.status === 'NEW' && (!ticket.assigneeId || owner)) actions.push('claim');
  if (admin && active) actions.push('assignments');
  if ((admin || owner) && active) actions.push('transfers');
  if (owner && ticket.status === 'IN_PROGRESS') actions.push('suspensions', 'resolutions');
  if (owner && ticket.status === 'ON_HOLD') actions.push('resume');
  if (ticket.status === 'RESOLVED' && (requester || engineer || admin)) actions.push('close');
  const withinReopen = ticket.status === 'RESOLVED' || (ticket.status === 'CLOSED' && ticket.closedAt !== null
    && now <= Date.parse(ticket.closedAt) + 7 * 24 * 60 * 60 * 1000);
  if (withinReopen && (requester || owner || admin)) actions.push('reopen');
  if (admin) actions.push('priority-changes');
  return actions;
}
