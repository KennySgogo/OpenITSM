// The backend startup log is authoritative. Override only this process variable when needed.
export const backendTarget = process.env.OPENITSM_BACKEND_URL || 'http://127.0.0.1:8080';
