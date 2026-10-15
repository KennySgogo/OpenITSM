export function passwordIssues(password: string, confirm: string): string[] {
  const issues: string[] = [];
  if ([...password].length < 12 || [...password].length > 64) issues.push('12～64 个字符');
  if (!/\p{L}/u.test(password)) issues.push('至少包含一个字母');
  if (!/\p{Nd}/u.test(password)) issues.push('至少包含一个数字');
  if (password !== confirm) issues.push('两次密码必须一致');
  return issues;
}
export function canAccess(role: string, path: string, view?: string): boolean {
  if (['/admin/users', '/dashboard', '/settings'].includes(path)) return role === 'ADMIN';
  if (path === '/tickets' && role === 'EMPLOYEE' && view && view !== 'MINE') return false;
  return true;
}
export function isLocked(until: string | null, now = Date.now()): boolean {
  return !!until && new Date(until).getTime() > now;
}
