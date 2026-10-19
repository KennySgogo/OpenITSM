import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ticketOperations } from '../src/utils/TicketPolicy.ts';
import type { Ticket } from '../src/types/ApiTypes.ts';

const ticket: Ticket = {
  id: 'ticket', number: 'ITSM-1', title: 'Issue', description: 'Description', businessType: 'IT', impactScope: 'Team',
  requesterId: 'employee', requesterName: 'Employee', assigneeId: null, assigneeName: null,
  status: 'NEW', priority: 'P3', version: 0, createdAt: '2026-10-16T00:00:00Z',
  resolutionSummary: null, closedAt: null, cycleStartedAt: '2026-10-16T00:00:00Z', sla: null
};
const engineer = { id: 'engineer', role: 'ENGINEER' as const };
const employee = { id: 'employee', role: 'EMPLOYEE' as const };
const admin = { id: 'admin', role: 'ADMIN' as const };

test('viewing a ticket grants no ownership: claim, acceptance and owner-only processing', () => {
  assert.deepEqual(ticketOperations(ticket, employee), []);
  assert.deepEqual(ticketOperations(ticket, engineer), ['claim']);
  assert.deepEqual(ticketOperations({ ...ticket, assigneeId: 'other' }, engineer), []);
  assert.ok(ticketOperations({ ...ticket, assigneeId: engineer.id }, engineer).includes('claim'));
  const processing = { ...ticket, status: 'IN_PROGRESS' as const, assigneeId: engineer.id };
  assert.deepEqual(ticketOperations(processing, engineer), ['transfers', 'suspensions', 'resolutions']);
  assert.deepEqual(ticketOperations(processing, { ...engineer, id: 'other' }), []);
  assert.deepEqual(ticketOperations({ ...processing, status: 'ON_HOLD' }, engineer), ['transfers', 'resume']);
});

test('admin actions and proxy closing follow distinct role and state rules', () => {
  assert.deepEqual(ticketOperations(ticket, admin), ['assignments', 'transfers', 'priority-changes']);
  const resolved = { ...ticket, status: 'RESOLVED' as const, assigneeId: engineer.id };
  assert.deepEqual(ticketOperations(resolved, employee), ['close', 'reopen']);
  assert.deepEqual(ticketOperations(resolved, { ...engineer, id: 'other' }), ['close']);
  assert.deepEqual(ticketOperations(resolved, admin), ['close', 'reopen', 'priority-changes']);
  assert.deepEqual(ticketOperations(resolved, { ...employee, id: 'stranger' }), []);
});

test('closed ticket reopen includes exactly seven days and requires a permitted actor', () => {
  const closed = { ...ticket, status: 'CLOSED' as const, assigneeId: engineer.id, closedAt: '2026-10-16T00:00:00Z' };
  const boundary = Date.parse(closed.closedAt) + 7 * 24 * 60 * 60 * 1000;
  assert.ok(ticketOperations(closed, employee, boundary).includes('reopen'));
  assert.ok(ticketOperations(closed, engineer, boundary).includes('reopen'));
  assert.deepEqual(ticketOperations(closed, admin, boundary + 1), ['priority-changes']);
  assert.deepEqual(ticketOperations(closed, employee, boundary + 1), []);
  assert.deepEqual(ticketOperations(closed, { ...engineer, id: 'other' }, boundary), []);
  assert.deepEqual(ticketOperations({ ...closed, closedAt: null }, employee, boundary), []);
});
