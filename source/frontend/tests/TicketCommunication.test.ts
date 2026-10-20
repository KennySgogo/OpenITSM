import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canReply, historyChanges, historyActionLabel } from '../src/utils/TicketCommunication.ts';
import type { Ticket, TicketHistoryOperation } from '../src/types/ApiTypes.ts';

const ticket = { requesterId: 'requester', assigneeId: 'someone-else', status: 'NEW' } as Ticket;
test('communication permissions cover resolved tickets and staff without ownership, but never closed tickets', () => {
  for (const status of ['NEW', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED'] as const) {
    const current = { ...ticket, status };
    assert.equal(canReply(current, { id: 'requester', role: 'EMPLOYEE' }), true);
    assert.equal(canReply(current, { id: 'requester', role: 'EMPLOYEE' }, true), false);
    assert.equal(canReply(current, { id: 'stranger', role: 'EMPLOYEE' }), false);
    for (const role of ['ENGINEER', 'ADMIN'] as const) {
      assert.equal(canReply(current, { id: 'staff', role }), true);
      assert.equal(canReply(current, { id: 'staff', role }, true), true);
    }
  }
  for (const role of ['EMPLOYEE', 'ENGINEER', 'ADMIN'] as const)
    for (const internal of [true, false]) assert.equal(canReply({ ...ticket, status: 'CLOSED' }, { id: 'requester', role }, internal), false);
});

test('operation descriptions show changed public fields and preserve user text without copying raw audit keys', () => {
  const event = {
    action: 'TICKET_RESOLUTIONS', details: {
      before: { status: 'IN_PROGRESS', assigneeId: 'old', priority: 'P3', summary: null, secret: 'hidden' },
      after: { status: 'RESOLVED', assigneeId: null, priority: 'P1', summary: '<script>text only</script>', secret: 'other' }
    }
  } as unknown as TicketHistoryOperation;
  assert.deepEqual(historyChanges(event), ['状态：处理中 → 已解决', '优先级：P3 → P1', '已清空负责人', '解决摘要：<script>text only</script>']);
  assert.deepEqual(historyChanges({ ...event, details: { before: event.details.before, after: event.details.before } }), []);
  assert.deepEqual(historyChanges({ ...event, details: { replyId: 'reply' } }), []);
  assert.equal(historyActionLabel('TICKET_INTERNAL_NOTE'), '添加内部备注');
  assert.equal(historyActionLabel('unexpected'), '工单操作');
});

test('rejected tickets disallow all new communication', () => {
  const rejected = { ...ticket, status: 'REJECTED' as const };
  for (const role of ['EMPLOYEE', 'ENGINEER', 'ADMIN'] as const) {
    assert.equal(canReply(rejected, { id: rejected.requesterId, role }), false);
    assert.equal(canReply(rejected, { id: rejected.requesterId, role }, true), false);
  }
});
