import { test } from 'node:test';
import assert from 'node:assert/strict';
import { passwordIssues, canAccess, isLocked } from '../src/utils/Policy.ts';

test('password matches Java Unicode letters, decimal digits and code point length', () => {
  assert.deepEqual(passwordIssues('中文密码测试你好世界甲乙１２', '中文密码测试你好世界甲乙１２'), []);
  assert.ok(passwordIssues('abcdefghijk1', 'other').some(x => x.includes('一致')));
  assert.ok(passwordIssues('abcdefghijk', 'abcdefghijk').length > 0);
  assert.ok(passwordIssues('a1' + '😀'.repeat(63), 'a1' + '😀'.repeat(63)).length > 0);
  assert.deepEqual(passwordIssues('a1' + '😀'.repeat(10), 'a1' + '😀'.repeat(10)), []);
});
test('route permissions deny employee assigned views and all non-admin member access', () => {
  assert.equal(canAccess('EMPLOYEE', '/tickets', 'ASSIGNED'), false);
  assert.equal(canAccess('EMPLOYEE', '/tickets', 'MINE'), true);
  assert.equal(canAccess('ENGINEER', '/admin/users'), false);
  assert.equal(canAccess('ADMIN', '/admin/users'), true);
  assert.equal(canAccess('EMPLOYEE', '/dashboard'), false);
});
test('unlock only appears while lockedUntil is in the future', () => {
  assert.equal(isLocked(null, 1000), false);
  assert.equal(isLocked('1970-01-01T00:00:02Z', 1000), true);
  assert.equal(isLocked('1970-01-01T00:00:01Z', 2000), false);
});

test('knowledge author and administration views follow current roles', () => {
  for (const role of ['EMPLOYEE', 'ENGINEER', 'ADMIN']) {
    assert.equal(canAccess(role, '/knowledge', 'PUBLISHED'), true);
    assert.equal(canAccess(role, '/knowledge/new'), role === 'ENGINEER');
    assert.equal(canAccess(role, '/knowledge', 'MINE'), role === 'ENGINEER');
    assert.equal(canAccess(role, '/knowledge', 'ALL'), role === 'ADMIN');
  }
});
