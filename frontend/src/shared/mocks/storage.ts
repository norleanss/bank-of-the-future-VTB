import { z } from 'zod';
import type {
  ClientNotification,
  CreditApplication,
  InfoRequest,
  StatusHistoryItem,
  UploadedDocument,
} from '../types/models';

export interface MockState {
  applications: CreditApplication[];
  documents: Record<string, UploadedDocument[]>;
  history: Record<string, StatusHistoryItem[]>;
  signing: Record<string, { code: string; expiresAt: string }>;
  notifications: ClientNotification[];
  infoRequests: Record<string, InfoRequest[]>;
  documentContents: Record<string, string>;
}
export const STORAGE_KEY = 'gamma-credit-cabinet:mock:v1';
const appGuard = z.custom<CreditApplication>((value: unknown) => {
  if (!value || typeof value !== 'object') return false;
  return (
    'id' in value &&
    typeof value.id === 'string' &&
    'companyId' in value &&
    'status' in value &&
    'availableActions' in value &&
    Array.isArray(value.availableActions)
  );
});
const schema = z.object({
  applications: z.array(appGuard),
  documents: z.record(
    z.array(
      z.object({
        id: z.string(),
        documentType: z.enum(['FINANCIAL_STATEMENT', 'BANK_STATEMENT', 'CHARTER', 'OTHER']),
        fileName: z.string(),
        mimeType: z.string(),
        sizeBytes: z.number(),
        uploadedAt: z.string(),
        infoRequestId: z.string().optional(),
      }),
    ),
  ),
  history: z.record(
    z.array(
      z.custom<StatusHistoryItem>(
        (value: unknown) => typeof value === 'object' && value !== null && 'toStatus' in value,
      ),
    ),
  ),
  signing: z.record(z.object({ code: z.string(), expiresAt: z.string() })),
  notifications: z.array(z.custom<ClientNotification>()).default([]),
  infoRequests: z.record(z.array(z.custom<InfoRequest>())).default({}),
  documentContents: z.record(z.string()).default({}),
});
export function readState(): MockState {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw)
    return {
      applications: [],
      documents: {},
      history: {},
      signing: {},
      notifications: [],
      infoRequests: {},
      documentContents: {},
    };
  try {
    return schema.parse(JSON.parse(raw));
  } catch {
    throw new Error('Не удалось прочитать демо-заявки. Сохранённые данные не изменены.');
  }
}
export function writeState(state: MockState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    throw new Error('Не удалось сохранить демо-заявку. Освободите место в браузере.');
  }
}
