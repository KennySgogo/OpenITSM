export type Role = 'EMPLOYEE' | 'ENGINEER' | 'ADMIN';
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
export const roleLabels: Record<Role, string> = { EMPLOYEE: '员工', ENGINEER: '工程师', ADMIN: '管理员' };
export const statusLabels: Record<MemberStatus, string> = { INVITED: '待激活', ACTIVE: '在职', DISABLED: '已停用' };
