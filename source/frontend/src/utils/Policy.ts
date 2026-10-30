export function passwordIssues(password: string, confirm: string): string[] {
  const issues: string[] = [];
  if ([...password].length < 6) issues.push('至少 6 个字符');
  if (password !== confirm) issues.push('两次密码必须一致');
  return issues;
}
export function canAccess(role: string, path: string, view?: string): boolean {
  if (path === '/ticket-types') return role !== 'EMPLOYEE';
  if (path === '/knowledge/new') return role === 'ENGINEER';
  if (path === '/knowledge' && view === 'MINE') return role === 'ENGINEER';
  if (path === '/knowledge' && view === 'ALL') return role === 'ADMIN';
  if (['/admin/users', '/dashboard', '/settings'].includes(path)) return role === 'ADMIN';
  if (path === '/tickets' && role === 'EMPLOYEE' && view && view !== 'MINE') return false;
  return true;
}
export function isLocked(until: string | null, now = Date.now()): boolean {
  return !!until && new Date(until).getTime() > now;
}
