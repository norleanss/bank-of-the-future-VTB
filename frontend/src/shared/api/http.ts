import { z } from 'zod';
import type { CreditApi } from './contract';
import { ApiError } from './errors';
import type { Dictionaries, User } from '../types/models';
import { readSession, saveSession } from '../lib/storage';

const problemSchema = z.object({
  detail: z.string().optional(),
  title: z.string().optional(),
  type: z.string().optional(),
  errors: z.array(z.object({ field: z.string(), message: z.string() })).optional(),
});
const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
async function request<T>(path: string, options: RequestInit = {}, binary = false): Promise<T> {
  const session = readSession();
  const headers = new Headers(options.headers);
  if (session) headers.set('Authorization', `Bearer ${session.accessToken}`);
  if (options.body && !(options.body instanceof FormData))
    headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { ...options, headers });
  } catch {
    throw new ApiError('Не удалось связаться с сервером. Проверьте подключение и повторите.', 0);
  }
  if (!response.ok) {
    const parsed = problemSchema.safeParse(await response.json().catch(() => undefined));
    if (response.status === 401) {
      saveSession(null);
      window.dispatchEvent(new Event('session-expired'));
    }
    throw new ApiError(
      response.status === 501
        ? 'Это действие пока недоступно. Попробуйте позже.'
        : parsed.success
          ? (parsed.data.detail ?? parsed.data.title ?? 'Не удалось выполнить действие.')
          : `Сервер вернул ошибку ${response.status}. Повторите позже.`,
      response.status,
      parsed.success ? parsed.data.errors : [],
      parsed.success ? parsed.data.type : undefined,
    );
  }
  if (response.status === 204) return undefined as T;
  if (binary) return (await response.blob()) as T;
  return (await response.json()) as T;
}
const json = (method: string, data?: unknown): RequestInit => ({
  method,
  ...(data === undefined ? {} : { body: JSON.stringify(data) }),
});
const appPath = (id: string) => `/applications/${encodeURIComponent(id)}`;
export const httpApi: CreditApi = {
  async getSession() {
    const session = readSession();
    if (!session) return null;
    try {
      return { ...session, user: await request<User>('/me') };
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return null;
      throw error;
    }
  },
  async login(role) {
    const session = await request<Awaited<ReturnType<CreditApi['login']>>>(
      '/auth/demo-login',
      json('POST', { role }),
    );
    saveSession(session);
    return session;
  },
  async logout() {
    saveSession(null);
  },
  async getDictionaries() {
    const data = await request<Partial<Dictionaries>>('/dictionaries');
    return {
      purposes: [],
      fundingModes: [],
      repaymentTypes: [],
      applicationStatuses: [],
      reasonCodes: [],
      ...data,
    };
  },
  getCompanies: () => request('/companies'),
  getCompany: (id) => request(`/companies/${encodeURIComponent(id)}`),
  getCreditProducts: () => request('/products'),
  getCreditProduct: (code) => request(`/products/${encodeURIComponent(code)}`),
  matchProducts: (data) => request('/products/match', json('POST', data)),
  createApplication: (data) => request('/applications', json('POST', data)),
  updateApplication: (id, data) => request(appPath(id), json('PATCH', data)),
  submitApplication: (id) => request(`${appPath(id)}/submit`, json('POST')),
  getApplication: (id) => request(appPath(id)),
  getApplicationHistory: (id) => request(`${appPath(id)}/history`),
  getApplications: (companyId, page = 0) =>
    request(
      `/applications?page=${page}&size=10${companyId ? `&companyId=${encodeURIComponent(companyId)}` : ''}`,
    ),
  cancelApplication: (id) => request(`${appPath(id)}/cancel`, json('POST')),
  acceptOffer: (id) => request(`${appPath(id)}/offer/accept`, json('POST')),
  declineOffer: (id) => request(`${appPath(id)}/offer/decline`, json('POST')),
  requestSigningCode: (id) => request(`${appPath(id)}/signing/request-code`, json('POST')),
  confirmSigning: (id, code) => request(`${appPath(id)}/signing/confirm`, json('POST', { code })),
  getDocuments: (id) => request(`${appPath(id)}/documents`),
  getDocumentContent: (id, documentId, backoffice = false) =>
    request(
      `${backoffice ? '/backoffice' : ''}${appPath(id)}/documents/${encodeURIComponent(documentId)}/content`,
      {},
      true,
    ),
  getNotifications: (unreadOnly = false, page = 0) =>
    request(`/notifications?unreadOnly=${unreadOnly}&page=${page}&size=20`),
  markNotificationRead: (id) =>
    request(`/notifications/${encodeURIComponent(id)}/read`, json('POST')),
  subscribeNotifications(onChange) {
    const session = readSession();
    if (!session || typeof EventSource === 'undefined') return () => {};
    const stream = new EventSource(
      `${baseUrl}/notifications/stream?access_token=${encodeURIComponent(session.accessToken)}`,
    );
    stream.addEventListener('notification', onChange);
    // Reconcile with the paginated API after every connection, including reconnects.
    stream.addEventListener('open', onChange);
    return () => stream.close();
  },
  uploadDocument(id, file, type) {
    const body = new FormData();
    body.append('file', file);
    body.append('documentType', type);
    return request(`${appPath(id)}/documents`, { method: 'POST', body });
  },
  respondInfo: (id, requestId, data) =>
    request(
      `${appPath(id)}/info-requests/${encodeURIComponent(requestId)}/response`,
      json('POST', data),
    ),
  getBackofficeApplications: (queue, page = 0) =>
    request(`/backoffice/applications?queue=${queue}&page=${page}&size=10`),
  getBackofficeApplication: (id) => request(`/backoffice${appPath(id)}`),
  makeDecision: (id, data) => request(`/backoffice${appPath(id)}/decision`, json('POST', data)),
  requestInfo: (id, data) => request(`/backoffice${appPath(id)}/info-requests`, json('POST', data)),
};
