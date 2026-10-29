export type Money = string;
export type Role = 'CLIENT' | 'UNDERWRITER';
export type ProductCode = 'LUMP_SUM_LOAN' | 'CREDIT_LINE' | 'OVERDRAFT' | 'GAMMA_START';
export type TermUnit = 'MONTHS' | 'DAYS';
export type RateUnit = 'PERCENT_PER_YEAR' | 'PERCENT_PER_DAY';
export type Purpose = 'WORKING_CAPITAL' | 'EQUIPMENT' | 'CASH_GAP' | 'REFINANCING' | 'STARTUP';
export type FundingMode = 'LUMP_SUM' | 'TRANCHES' | 'ON_DEMAND';
export type RepaymentType = 'ANNUITY' | 'INTEREST_ONLY';
export type ApplicationStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'ENRICHMENT'
  | 'SCORING'
  | 'NEED_INFO'
  | 'MANUAL_REVIEW'
  | 'APPROVED'
  | 'COUNTER_OFFER'
  | 'PENDING_CONFIRMATION'
  | 'OFFER_ACCEPTED'
  | 'SIGNED'
  | 'DISBURSED'
  | 'REJECTED'
  | 'OFFER_DECLINED'
  | 'OFFER_EXPIRED'
  | 'CANCELLED';
export type ApplicationAction =
  | 'EDIT'
  | 'SUBMIT'
  | 'CANCEL'
  | 'RESPOND_INFO'
  | 'UPLOAD_DOCUMENT'
  | 'ACCEPT_OFFER'
  | 'DECLINE_OFFER'
  | 'SIGN';
export type DocumentType = 'FINANCIAL_STATEMENT' | 'CHARTER' | 'BANK_STATEMENT' | 'OTHER';
export interface UserCompany {
  id: string;
  name: string;
  inn: string;
  businessType: 'LLC' | 'IP';
  demoScenario?: string;
}
export interface Company extends UserCompany {
  ogrn: string;
  registrationDate: string;
  businessAgeMonths?: number;
  status: 'ACTIVE' | 'LIQUIDATING' | 'LIQUIDATED';
  okved?: string;
  region?: string;
}
export interface User {
  id: string;
  fullName: string;
  role: Role;
  companies?: UserCompany[];
}
export interface Session {
  accessToken: string;
  expiresIn: number;
  user: User;
}
export interface CreditProduct {
  code: ProductCode;
  name: string;
  description?: string;
  minAmount: Money;
  maxAmount: Money;
  termKind: 'LOAN' | 'TRANCHE';
  termUnit: TermUnit;
  minTerm: number;
  maxTerm: number;
  maxTermWithSubscription?: number;
  agreementTermMonths?: number;
  autoRenewal: boolean;
  rateUnit: RateUnit;
  baseRate: number;
  rateWithSubscription?: number;
  subscriptionDescription?: string;
  subscriptionFeePercent?: number;
  fundingModes: FundingMode[];
  repaymentTypes: RepaymentType[];
  minBusinessAgeMonths: number;
}
export interface SelectionData {
  companyId: string;
  purpose: Purpose;
  requestedAmount: Money;
  term: number;
  termUnit: TermUnit;
  fundingMode: FundingMode;
}
export interface ProductMatch {
  product: { code: ProductCode; name: string };
  suitable: boolean;
  requiresSubscription: boolean;
  recommended: boolean;
  reasons: string[];
  estimate?: PaymentEstimate;
}
export interface PaymentEstimate {
  monthlyPayment?: Money;
  monthlyPaymentWithSubscription?: Money;
  subscriptionFee?: Money;
  repaymentType: RepaymentType;
}
export interface Financials {
  annualRevenue?: Money;
  annualExpenses?: Money;
  bankMonthlyTurnover?: Money;
  monthlyDebtPayment?: Money;
}
export interface ApplicationCreateRequest extends Partial<Omit<SelectionData, 'companyId'>> {
  companyId: string;
  productCode?: ProductCode;
  repaymentType?: RepaymentType;
  financials?: Financials;
  collateral?: boolean;
  subscription?: boolean;
}
export type ApplicationPatchRequest = {
  [K in keyof Omit<
    ApplicationCreateRequest,
    'companyId' | 'financials' | 'subscription' | 'collateral'
  >]?: ApplicationCreateRequest[K] | null;
} & {
  financials?: { [K in keyof Financials]?: Money | null } | null;
  subscription?: boolean;
  collateral?: boolean;
};
export interface Offer {
  productCode: ProductCode;
  productName: string;
  amount: Money;
  term: number;
  termUnit: TermUnit;
  termKind: 'LOAN' | 'TRANCHE';
  agreementTermMonths?: number;
  rate: number;
  rateUnit: RateUnit;
  monthlyPayment?: Money;
  subscription: boolean;
  subscriptionFee?: Money;
  preliminary: boolean;
  expiresAt: string;
}
export interface InfoRequest {
  id: string;
  message: string;
  requestedFields: string[];
  requestedDocuments: DocumentType[];
  createdBy: 'SYSTEM' | 'EMPLOYEE';
  createdAt: string;
  status: 'OPEN' | 'ANSWERED';
  response?: string;
}
export interface CreditApplication extends ApplicationCreateRequest {
  id: string;
  number: string;
  status: ApplicationStatus;
  offer?: Offer;
  openInfoRequest?: InfoRequest;
  reasons: DecisionReason[];
  availableActions: ApplicationAction[];
  nextStepHint?: string;
  createdAt: string;
  submittedAt?: string;
  updatedAt: string;
}
export interface ApplicationSummary {
  id: string;
  number: string;
  status: ApplicationStatus;
  companyId: string;
  companyName: string;
  purpose?: Purpose;
  productCode?: ProductCode;
  requestedAmount?: Money;
  createdAt: string;
  updatedAt: string;
  unreadNotifications?: number;
}
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface Dictionaries {
  purposes: { code: Purpose; label: string; description?: string }[];
  fundingModes: { code: FundingMode; label: string }[];
  repaymentTypes: { code: RepaymentType; label: string }[];
  applicationStatuses: { code: ApplicationStatus; label: string }[];
  reasonCodes: { code: string; label: string }[];
}
export interface UploadedDocument {
  id: string;
  documentType: DocumentType;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  uploadedAt: string;
  infoRequestId?: string;
}
export interface DecisionReason {
  code: string;
  message: string;
  advice?: string;
}
export interface ClientNotification {
  id: string;
  type:
    | 'APPLICATION_SUBMITTED'
    | 'INFO_REQUESTED'
    | 'DECISION_MADE'
    | 'OFFER_CONFIRMED'
    | 'OFFER_EXPIRED'
    | 'SIGNED'
    | 'DISBURSED'
    | 'STATUS_CHANGED';
  title: string;
  body: string;
  applicationId?: string;
  read: boolean;
  createdAt: string;
}
export interface StatusHistoryItem {
  fromStatus?: ApplicationStatus;
  toStatus: ApplicationStatus;
  actorType: 'CLIENT' | 'EMPLOYEE' | 'SYSTEM';
  actorName?: string;
  comment?: string;
  createdAt: string;
}
export interface ManualDecisionRequest {
  decision: 'APPROVE' | 'COUNTER_OFFER' | 'REJECT';
  productCode?: ProductCode;
  amount?: Money;
  term?: number;
  termUnit?: TermUnit;
  rate?: number;
  comment?: string;
  reasonCodes?: string[];
}
export interface BackofficeSummary {
  id: string;
  number: string;
  status: ApplicationStatus;
  companyName: string;
  inn: string;
  productCode?: ProductCode;
  requestedAmount?: Money;
  score?: number;
  grade?: string;
  reasons?: DecisionReason[];
  submittedAt?: string;
  waitingSince?: string;
}
export interface BackofficeDetail {
  application: CreditApplication;
  company: Company;
  history: StatusHistoryItem[];
  infoRequests: InfoRequest[];
  documents: UploadedDocument[];
  enrichment?: {
    sources: {
      source: 'EGRUL' | 'FNS' | 'BKI';
      status: 'OK' | 'NOT_FOUND' | 'ERROR' | 'TIMEOUT';
      receivedAt?: string;
    }[];
    taxDebtAmount?: Money;
    activeCreditsCount?: number;
    totalDebtAmount?: Money;
    maxOverdueDays12m?: number;
  };
  scoring?: {
    score: number;
    grade?: string;
    factors: {
      factor: string;
      points: number;
      maxPoints?: number;
      inputValue?: string;
      explanation?: string;
    }[];
  };
}
export interface ApplicationFormData extends SelectionData {
  productCode: ProductCode;
  repaymentType: RepaymentType;
  subscription: boolean;
  annualRevenue: string;
  annualExpenses: string;
  bankMonthlyTurnover: string;
  monthlyDebtPayment: string;
}
export interface Workspace {
  selection: Partial<SelectionData>;
  selectionStep: number;
  applicationDraft: Partial<ApplicationFormData>;
  applicationStep: number;
  applicationId?: string;
}
