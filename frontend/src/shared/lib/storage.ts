import { z } from 'zod';
import type { Session, Workspace } from '../types/models';

const scope = import.meta.env.VITE_API_MODE === 'http' ? 'http' : 'mock';
const SESSION_KEY = `gamma-credit:session:v1:${scope}`;
const WORKSPACE_KEY = `gamma-credit:workspaces:v1:${scope}`;
const COMPANY_KEY = `gamma-credit:company:v1:${scope}`;
const sessionSchema = z.object({
  accessToken: z.string(),
  expiresIn: z.number(),
  user: z.object({
    id: z.string(),
    fullName: z.string(),
    role: z.enum(['CLIENT', 'UNDERWRITER']),
    companies: z
      .array(
        z.object({
          id: z.string(),
          name: z.string(),
          inn: z.string(),
          businessType: z.enum(['LLC', 'IP']),
          demoScenario: z.string().optional(),
        }),
      )
      .optional(),
  }),
});
export function readSession(): Session | null {
  const raw = localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    const stored = z
      .object({ session: sessionSchema, expiresAt: z.number() })
      .parse(JSON.parse(raw));
    return stored.expiresAt > Date.now() ? stored.session : null;
  } catch {
    return null;
  }
}
export function saveSession(session: Session | null): void {
  if (session)
    localStorage.setItem(
      SESSION_KEY,
      JSON.stringify({
        session,
        expiresAt: Date.now() + session.expiresIn * 1000,
      }),
    );
  else localStorage.removeItem(SESSION_KEY);
}
export function getSelectedCompanyId(): string {
  return localStorage.getItem(COMPANY_KEY) ?? '';
}
export function selectCompany(id: string): void {
  localStorage.setItem(COMPANY_KEY, id);
}
export const emptyWorkspace = (): Workspace => ({
  selection: {},
  selectionStep: 0,
  applicationDraft: {},
  applicationStep: 0,
});
const workspaceSchema = z.object({
  selection: z
    .object({
      companyId: z.string(),
      purpose: z.enum(['WORKING_CAPITAL', 'EQUIPMENT', 'CASH_GAP', 'REFINANCING', 'STARTUP']),
      requestedAmount: z.string(),
      term: z.number(),
      termUnit: z.enum(['MONTHS', 'DAYS']),
      fundingMode: z.enum(['LUMP_SUM', 'TRANCHES', 'ON_DEMAND']),
    })
    .partial(),
  selectionStep: z.number().int().min(0).max(3),
  applicationDraft: z
    .object({
      companyId: z.string(),
      productCode: z.enum(['LUMP_SUM_LOAN', 'CREDIT_LINE', 'OVERDRAFT', 'GAMMA_START']),
      purpose: z.enum(['WORKING_CAPITAL', 'EQUIPMENT', 'CASH_GAP', 'REFINANCING', 'STARTUP']),
      requestedAmount: z.string(),
      term: z.number(),
      termUnit: z.enum(['MONTHS', 'DAYS']),
      fundingMode: z.enum(['LUMP_SUM', 'TRANCHES', 'ON_DEMAND']),
      repaymentType: z.enum(['ANNUITY', 'INTEREST_ONLY']),
      subscription: z.boolean(),
      annualRevenue: z.string(),
      annualExpenses: z.string(),
      bankMonthlyTurnover: z.string(),
      monthlyDebtPayment: z.string(),
    })
    .partial(),
  applicationStep: z.number().int().min(0).max(3),
  applicationId: z.string().optional(),
});
function readWorkspaces(): Record<string, Workspace> {
  try {
    return z.record(workspaceSchema).parse(JSON.parse(localStorage.getItem(WORKSPACE_KEY) ?? '{}'));
  } catch {
    return {};
  }
}
export function getWorkspace(companyId = getSelectedCompanyId()): Workspace {
  return readWorkspaces()[companyId] ?? emptyWorkspace();
}
export function saveWorkspace(data: Partial<Workspace>, companyId = getSelectedCompanyId()): void {
  const all = readWorkspaces();
  all[companyId] = { ...(all[companyId] ?? emptyWorkspace()), ...data };
  try {
    localStorage.setItem(WORKSPACE_KEY, JSON.stringify(all));
  } catch {
    throw new Error('Не удалось сохранить черновик. Освободите место в браузере.');
  }
}
export function clearApplicationDraft(companyId: string): void {
  const previous = getWorkspace(companyId);
  saveWorkspace({ ...emptyWorkspace(), selection: previous.selection }, companyId);
}
