import type {
  ApplicationCreateRequest,
  ApplicationPatchRequest,
  ApplicationStatus,
  ApplicationSummary,
  BackofficeDetail,
  BackofficeSummary,
  Company,
  ClientNotification,
  CreditApplication,
  CreditProduct,
  Dictionaries,
  DocumentType,
  InfoRequest,
  ManualDecisionRequest,
  Page,
  ProductMatch,
  Role,
  SelectionData,
  Session,
  StatusHistoryItem,
  UploadedDocument,
} from '../types/models';

export interface CreditApi {
  getSession(): Promise<Session | null>;
  login(role: Role): Promise<Session>;
  logout(): Promise<void>;
  getDictionaries(): Promise<Dictionaries>;
  getCompanies(): Promise<Company[]>;
  getCompany(id: string): Promise<Company>;
  getCreditProducts(): Promise<CreditProduct[]>;
  getCreditProduct(code: string): Promise<CreditProduct>;
  matchProducts(data: SelectionData): Promise<{ items: ProductMatch[] }>;
  createApplication(data: ApplicationCreateRequest): Promise<CreditApplication>;
  updateApplication(id: string, data: ApplicationPatchRequest): Promise<CreditApplication>;
  submitApplication(id: string): Promise<CreditApplication>;
  getApplication(id: string): Promise<CreditApplication>;
  getApplicationHistory(id: string): Promise<StatusHistoryItem[]>;
  getApplications(companyId?: string, page?: number): Promise<Page<ApplicationSummary>>;
  cancelApplication(id: string): Promise<CreditApplication>;
  acceptOffer(id: string): Promise<CreditApplication>;
  declineOffer(id: string): Promise<CreditApplication>;
  requestSigningCode(id: string): Promise<{ expiresAt: string; demoCode?: string }>;
  confirmSigning(id: string, code: string): Promise<CreditApplication>;
  getDocuments(id: string): Promise<UploadedDocument[]>;
  getDocumentContent(id: string, documentId: string, backoffice?: boolean): Promise<Blob>;
  getNotifications(unreadOnly?: boolean, page?: number): Promise<Page<ClientNotification>>;
  markNotificationRead(id: string): Promise<void>;
  subscribeNotifications?(onChange: () => void): () => void;
  uploadDocument(id: string, file: File, type: DocumentType): Promise<UploadedDocument>;
  respondInfo(
    id: string,
    requestId: string,
    data: { comment?: string; documentIds: string[] },
  ): Promise<CreditApplication>;
  getBackofficeApplications(
    queue: 'REVIEW' | 'CONFIRMATION' | 'ALL',
    page?: number,
  ): Promise<Page<BackofficeSummary>>;
  getBackofficeApplication(id: string): Promise<BackofficeDetail>;
  makeDecision(id: string, data: ManualDecisionRequest): Promise<CreditApplication>;
  requestInfo(
    id: string,
    data: Pick<InfoRequest, 'message' | 'requestedFields' | 'requestedDocuments'>,
  ): Promise<InfoRequest>;
  setDemoDecision?(id: string, status: ApplicationStatus): Promise<CreditApplication>;
}
