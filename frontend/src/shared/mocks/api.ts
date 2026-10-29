import type { CreditApi } from '../api/contract';
import { ApiError } from '../api/errors';
import type {
  ApplicationAction,
  ApplicationFormData,
  ApplicationStatus,
  CreditApplication,
  CreditProduct,
  Financials,
  Offer,
  Page,
  StatusHistoryItem,
} from '../types/models';
import { schemaForProduct } from '../../entities/credit/validation';
import { statusLabels } from '../../entities/application/status';
import { fromKopecks, toKopecks } from '../lib/money';
import { readSession, saveSession, selectCompany, getSelectedCompanyId } from '../lib/storage';
import { companies, dictionaries, products } from './fixtures';
import { matchProducts } from './matchProducts';
import { paymentEstimate } from './paymentEstimate';
import { readState, writeState, type MockState } from './storage';

const wait = () => new Promise<void>((resolve) => setTimeout(resolve, 250));
export const DEMO_STAGE_MS = 2_000;
const processing: ApplicationStatus[] = ['SUBMITTED', 'ENRICHMENT', 'SCORING'];
const actions: Partial<Record<ApplicationStatus, ApplicationAction[]>> = {
  DRAFT: ['EDIT', 'SUBMIT', 'CANCEL'],
  NEED_INFO: ['RESPOND_INFO', 'UPLOAD_DOCUMENT', 'CANCEL'],
  MANUAL_REVIEW: ['CANCEL'],
  APPROVED: ['ACCEPT_OFFER', 'DECLINE_OFFER'],
  COUNTER_OFFER: ['ACCEPT_OFFER', 'DECLINE_OFFER'],
  OFFER_ACCEPTED: ['SIGN'],
};
function user() {
  const session = readSession();
  if (!session) throw new ApiError('Войдите в кабинет, чтобы продолжить.', 401);
  return session.user;
}
function bank() {
  if (user().role !== 'UNDERWRITER') throw new ApiError('Доступно только сотруднику банка.', 403);
}
function company(id: string, backoffice = false) {
  const current = user();
  if (
    !backoffice &&
    (current.role !== 'CLIENT' || !current.companies?.some((item) => item.id === id))
  )
    throw new ApiError('Компания недоступна этому пользователю.', 403);
  const found = companies.find((item) => item.id === id);
  if (!found) throw new ApiError('Компания не найдена.', 403);
  return found;
}
function application(id: string, backoffice = false) {
  if (backoffice) bank();
  const state = readState();
  const app = state.applications.find((item) => item.id === id);
  if (!app) throw new ApiError('Заявка не найдена. Откройте список заявок в кабинете.', 404);
  company(app.companyId, backoffice);
  progressApplication(state, app);
  return { state, app };
}
function transition(
  state: MockState,
  app: CreditApplication,
  status: ApplicationStatus,
  actor: StatusHistoryItem['actorType'] = 'SYSTEM',
) {
  if (app.status !== status) {
    (state.history[app.id] ??= []).push({
      fromStatus: app.status,
      toStatus: status,
      actorType: actor,
      ...(actor === 'EMPLOYEE' ? { actorName: user().fullName } : {}),
      createdAt: new Date().toISOString(),
    });
    if (
      [
        'SUBMITTED',
        'NEED_INFO',
        'MANUAL_REVIEW',
        'APPROVED',
        'COUNTER_OFFER',
        'REJECTED',
        'PENDING_CONFIRMATION',
        'OFFER_EXPIRED',
        'SIGNED',
        'DISBURSED',
      ].includes(status) ||
      (status === 'OFFER_ACCEPTED' && actor === 'EMPLOYEE')
    ) {
      state.notifications.unshift({
        id: crypto.randomUUID(),
        type:
          status === 'SUBMITTED'
            ? 'APPLICATION_SUBMITTED'
            : status === 'NEED_INFO'
              ? 'INFO_REQUESTED'
              : status === 'SIGNED'
                ? 'SIGNED'
                : status === 'DISBURSED'
                  ? 'DISBURSED'
                  : status === 'OFFER_EXPIRED'
                    ? 'OFFER_EXPIRED'
                    : status === 'OFFER_ACCEPTED'
                      ? 'OFFER_CONFIRMED'
                      : ['APPROVED', 'COUNTER_OFFER', 'REJECTED'].includes(status)
                        ? 'DECISION_MADE'
                        : 'STATUS_CHANGED',
        title: statusLabels[status],
        body: `Заявка ${app.number} · ${company(app.companyId, true).name}`,
        applicationId: app.id,
        read: false,
        createdAt: new Date().toISOString(),
      });
    }
  }
  app.status = status;
  app.availableActions = [...(actions[status] ?? [])];
  app.updatedAt = new Date().toISOString();
  app.nextStepHint =
    status === 'OFFER_EXPIRED'
      ? 'Срок действия предложения истёк. Вы можете подать новую заявку.'
      : status === 'PENDING_CONFIRMATION'
        ? 'Сотрудник банка подтверждает предложенные условия.'
        : status === 'MANUAL_REVIEW'
          ? 'Специалист банка рассматривает вашу заявку.'
          : status === 'DISBURSED'
            ? 'Деньги выданы. Спасибо, что выбрали нас.'
            : undefined;
}
function productFor(app: CreditApplication): CreditProduct {
  const found = products.find((item) => item.code === app.productCode);
  if (!found)
    throw new ApiError('Выберите кредитный продукт.', 400, [
      { field: 'productCode', message: 'Выберите продукт' },
    ]);
  return found;
}
function offerFor(app: CreditApplication, changes = false, rateOverride?: number): Offer {
  const product = productFor(app);
  const reduced = (toKopecks(app.requestedAmount ?? '0.00') * 8n) / 10n;
  const amount = changes
    ? fromKopecks(reduced < toKopecks(product.minAmount) ? toKopecks(product.minAmount) : reduced)
    : (app.requestedAmount ?? '0.00');
  const subscription = app.subscription ?? false;
  const baseRate = subscription
    ? (product.rateWithSubscription ?? product.baseRate)
    : product.baseRate;
  const rate =
    rateOverride ??
    (changes && amount === app.requestedAmount
      ? Math.round((baseRate + (product.rateUnit === 'PERCENT_PER_DAY' ? 0.01 : 0.5)) * 100) / 100
      : baseRate);
  const offer: Offer = {
    productCode: product.code,
    productName: product.name,
    amount,
    term: app.term ?? product.maxTerm,
    termUnit: app.termUnit ?? product.termUnit,
    termKind: product.termKind,
    rate,
    rateUnit: product.rateUnit,
    subscription,
    preliminary: app.companyId === companies[3]?.id,
    expiresAt: new Date(Date.now() + 7 * 86400_000).toISOString(),
  };
  if (product.agreementTermMonths) offer.agreementTermMonths = product.agreementTermMonths;
  if (subscription && product.subscriptionFeePercent)
    offer.subscriptionFee = fromKopecks(
      (toKopecks(amount) * BigInt(Math.round(product.subscriptionFeePercent * 100))) / 10_000n,
    );
  offer.monthlyPayment = paymentEstimate(
    { ...product, baseRate: rate },
    {
      requestedAmount: amount,
      term: offer.term,
      termUnit: offer.termUnit,
    },
  ).monthlyPayment;
  return offer;
}
function decide(state: MockState, app: CreditApplication, status: ApplicationStatus) {
  delete app.offer;
  delete app.openInfoRequest;
  app.reasons = [];
  if (status === 'APPROVED' || status === 'COUNTER_OFFER') {
    app.offer = offerFor(app, status === 'COUNTER_OFFER');
    if (status === 'COUNTER_OFFER')
      app.reasons = [
        {
          code: 'LIMIT_REDUCED',
          message: 'Предлагаем скорректированные условия по результатам демонстрационной оценки.',
          advice: 'Ознакомьтесь с новыми условиями и примите предложение, если оно вам подходит.',
        },
      ];
  }
  if (status === 'NEED_INFO')
    app.openInfoRequest = {
      id: crypto.randomUUID(),
      message: 'Уточните выручку и расходы за последние 12 месяцев.',
      requestedFields: ['financials.annualRevenue', 'financials.annualExpenses'],
      requestedDocuments: [],
      createdBy: 'SYSTEM',
      createdAt: new Date().toISOString(),
      status: 'OPEN',
    };
  if (status === 'REJECTED')
    app.reasons = [
      {
        code: app.companyId === companies[5]?.id ? 'COMPANY_LIQUIDATING' : 'OVERDUE_GT_60',
        message:
          app.companyId === companies[5]?.id
            ? 'Компания находится в процессе ликвидации.'
            : 'В демо-истории компании есть просрочка более 60 дней.',
        advice: 'Подайте новую заявку, когда причина отказа будет устранена.',
      },
    ];
  if (status === 'OFFER_EXPIRED') {
    app.offer = offerFor(app);
    app.offer.expiresAt = new Date(Date.now() - 1000).toISOString();
  }
  transition(state, app, status);
}
function progressApplication(state: MockState, app: CreditApplication, now = Date.now()): void {
  if (
    (app.status === 'APPROVED' || app.status === 'COUNTER_OFFER') &&
    app.offer &&
    Date.parse(app.offer.expiresAt) <= now
  )
    transition(state, app, 'OFFER_EXPIRED');
  if (!app.submittedAt || !processing.includes(app.status)) return;
  const stage = Math.floor((now - Date.parse(app.submittedAt)) / DEMO_STAGE_MS);
  if (stage < 3) {
    const status = processing[Math.max(processing.indexOf(app.status), 0, stage)];
    if (status && app.status !== status) transition(state, app, status);
    return;
  }
  const status =
    app.companyId === companies[1]?.id
      ? 'MANUAL_REVIEW'
      : app.companyId === companies[2]?.id || app.companyId === companies[5]?.id
        ? 'REJECTED'
        : 'APPROVED';
  decide(state, app, status);
}
function checkAction(app: CreditApplication, action: ApplicationAction) {
  if (action === 'ACCEPT_OFFER' && app.status === 'OFFER_EXPIRED')
    throw new ApiError(
      'Срок предложения истёк. Подайте новую заявку.',
      409,
      [],
      'https://credit.example/problems/offer-expired',
    );
  if (!app.availableActions.includes(action))
    throw new ApiError(
      'Действие уже недоступно. Обновите заявку.',
      409,
      [],
      'https://credit.example/problems/invalid-transition',
    );
}
function formData(app: CreditApplication): ApplicationFormData {
  return {
    companyId: app.companyId,
    productCode: app.productCode ?? 'LUMP_SUM_LOAN',
    purpose: app.purpose ?? 'WORKING_CAPITAL',
    requestedAmount: app.requestedAmount ?? '',
    term: app.term ?? 0,
    termUnit: app.termUnit ?? 'MONTHS',
    fundingMode: app.fundingMode ?? 'LUMP_SUM',
    repaymentType: app.repaymentType ?? 'ANNUITY',
    subscription: app.subscription ?? false,
    annualRevenue: app.financials?.annualRevenue ?? '',
    annualExpenses: app.financials?.annualExpenses ?? '',
    bankMonthlyTurnover: app.financials?.bankMonthlyTurnover ?? '',
    monthlyDebtPayment: app.financials?.monthlyDebtPayment ?? '',
  };
}
function pageOf<T>(content: T[], page = 0): Page<T> {
  const size = 10;
  return {
    content: content.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: content.length,
    totalPages: Math.ceil(content.length / size),
  };
}
function persist(state: MockState, app: CreditApplication) {
  writeState(state);
  return structuredClone(app);
}
export const mockApi: CreditApi = {
  async getSession() {
    await wait();
    return readSession();
  },
  async login(role) {
    await wait();
    const session = {
      accessToken: `demo-${role.toLowerCase()}`,
      expiresIn: 86400,
      user:
        role === 'CLIENT'
          ? {
              id: '7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f',
              fullName: 'Иван Петров',
              role,
              companies: companies.map(({ id, name, inn, businessType }, i) => ({
                id,
                name,
                inn,
                businessType,
                demoScenario: [
                  'Одобрение',
                  'Ручная проверка',
                  'Отказ: просрочка',
                  'Предварительное одобрение',
                  'Гамма-Старт',
                  'Отказ: ликвидация',
                ][i],
              })),
            }
          : {
              id: '5c3e7a9d-1f4b-4b2e-8d6a-9e2c4f7b1a38',
              fullName: 'Мария Смирнова',
              role,
            },
    };
    saveSession(session);
    if (role === 'CLIENT' && !companies.some((item) => item.id === getSelectedCompanyId()))
      selectCompany(companies[0]!.id);
    return session;
  },
  async logout() {
    await wait();
    saveSession(null);
  },
  async getDictionaries() {
    await wait();
    user();
    return structuredClone(dictionaries);
  },
  async getCompanies() {
    await wait();
    return structuredClone(
      companies.filter((item) => {
        company(item.id);
        return true;
      }),
    );
  },
  async getCompany(id) {
    await wait();
    return structuredClone(company(id));
  },
  async getCreditProducts() {
    await wait();
    user();
    return structuredClone(products);
  },
  async getCreditProduct(code) {
    await wait();
    user();
    const found = products.find((item) => item.code === code);
    if (!found) throw new ApiError('Продукт не найден. Вернитесь к списку кредитов.', 404);
    return structuredClone(found);
  },
  async matchProducts(data) {
    await wait();
    const current = company(data.companyId);
    return {
      items: matchProducts(data, current.businessAgeMonths ?? 0),
    };
  },
  async createApplication(data) {
    await wait();
    company(data.companyId);
    const state = readState(),
      now = new Date().toISOString();
    const app: CreditApplication = {
      ...data,
      id: crypto.randomUUID(),
      number: `CR-2026-${String(state.applications.length + 1).padStart(6, '0')}`,
      status: 'DRAFT',
      reasons: [],
      availableActions: ['EDIT', 'SUBMIT', 'CANCEL'],
      createdAt: now,
      updatedAt: now,
    };
    state.applications.unshift(app);
    state.history[app.id] = [{ toStatus: 'DRAFT', actorType: 'CLIENT', createdAt: now }];
    return persist(state, app);
  },
  async updateApplication(id, data) {
    await wait();
    const { state, app } = application(id);
    if (app.status !== 'DRAFT' && app.status !== 'NEED_INFO')
      throw new ApiError('Анкету сейчас нельзя изменить.', 409);
    const allowed = [
      'productCode',
      'purpose',
      'requestedAmount',
      'term',
      'termUnit',
      'fundingMode',
      'repaymentType',
      'financials',
      'subscription',
      'collateral',
    ];
    if (Object.keys(data).some((key) => !allowed.includes(key)))
      throw new ApiError('Запрос содержит недоступное поле.', 400);
    if (
      app.status === 'NEED_INFO' &&
      Object.keys(data).some(
        (key) =>
          key !== 'financials' &&
          (!['purpose', 'fundingMode', 'repaymentType'].includes(key) ||
            !app.openInfoRequest?.requestedFields.includes(key)),
      )
    )
      throw new ApiError('После отправки можно изменить только запрошенные сведения.', 400);
    if ('term' in data !== 'termUnit' in data)
      throw new ApiError('Срок и единица передаются вместе.', 400);
    const { financials, ...fields } = data;
    Object.assign(app, fields);
    for (const key of Object.keys(fields)) {
      if (Reflect.get(app, key) === null) Reflect.deleteProperty(app, key);
    }
    if (financials === null) delete app.financials;
    else if (financials) {
      const merged: Financials = { ...app.financials };
      for (const [key, value] of Object.entries(financials)) {
        if (value === null) Reflect.deleteProperty(merged, key);
        else Reflect.set(merged, key, value);
      }
      app.financials = merged;
    }
    app.updatedAt = new Date().toISOString();
    return persist(state, app);
  },
  async submitApplication(id) {
    await wait();
    const { state, app } = application(id);
    if (app.status !== 'DRAFT') return persist(state, app);
    const missing = (
      [
        'productCode',
        'purpose',
        'requestedAmount',
        'term',
        'termUnit',
        'fundingMode',
        'repaymentType',
      ] as const
    ).filter((field) => app[field] === undefined);
    if (missing.length)
      throw new ApiError(
        'Заполните обязательные поля.',
        400,
        missing.map((field) => ({ field, message: 'Заполните поле' })),
      );
    const product = productFor(app);
    const parsed = schemaForProduct(product).safeParse(formData(app));
    if (!parsed.success)
      throw new ApiError(
        'Проверьте поля заявки.',
        400,
        parsed.error.issues.map((issue) => ({
          field: [
            'annualRevenue',
            'annualExpenses',
            'bankMonthlyTurnover',
            'monthlyDebtPayment',
          ].includes(String(issue.path[0]))
            ? `financials.${issue.path[0]}`
            : issue.path.join('.'),
          message: issue.message,
        })),
      );
    if ((company(app.companyId).businessAgeMonths ?? 0) < product.minBusinessAgeMonths)
      throw new ApiError('Возраст бизнеса не соответствует условиям продукта.', 400, [
        { field: 'productCode', message: 'Выберите другой продукт' },
      ]);
    app.submittedAt = new Date().toISOString();
    transition(state, app, 'SUBMITTED', 'CLIENT');
    return persist(state, app);
  },
  async getApplication(id) {
    await wait();
    const { state, app } = application(id);
    return persist(state, app);
  },
  async getApplicationHistory(id) {
    await wait();
    const { state } = application(id);
    writeState(state);
    return structuredClone(
      (state.history[id] ?? []).map((event) => {
        const clientEvent = { ...event };
        delete clientEvent.actorName;
        return clientEvent;
      }),
    );
  },
  async getApplications(companyId, page) {
    await wait();
    const current = user();
    if (current.role !== 'CLIENT') throw new ApiError('Откройте кабинет сотрудника.', 403);
    if (companyId) company(companyId);
    const state = readState();
    state.applications.forEach((app) => progressApplication(state, app));
    writeState(state);
    return pageOf(
      state.applications
        .filter(
          (app) =>
            current.companies?.some((item) => item.id === app.companyId) &&
            (!companyId || app.companyId === companyId),
        )
        .map((app) => ({
          id: app.id,
          number: app.number,
          companyId: app.companyId,
          companyName: company(app.companyId).name,
          status: app.status,
          productCode: app.productCode,
          requestedAmount: app.requestedAmount,
          createdAt: app.createdAt,
          updatedAt: app.updatedAt,
          unreadNotifications: state.notifications.filter(
            (item) => item.applicationId === app.id && !item.read,
          ).length,
        })),
      page,
    );
  },
  async cancelApplication(id) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'CANCEL');
    transition(state, app, 'CANCELLED', 'CLIENT');
    return persist(state, app);
  },
  async acceptOffer(id) {
    await wait();
    const { state, app } = application(id);
    writeState(state);
    checkAction(app, 'ACCEPT_OFFER');
    transition(
      state,
      app,
      app.offer?.preliminary ? 'PENDING_CONFIRMATION' : 'OFFER_ACCEPTED',
      'CLIENT',
    );
    return persist(state, app);
  },
  async declineOffer(id) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'DECLINE_OFFER');
    transition(state, app, 'OFFER_DECLINED', 'CLIENT');
    return persist(state, app);
  },
  async requestSigningCode(id) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'SIGN');
    const expiresAt = new Date(Date.now() + 5 * 60_000).toISOString();
    const code = String(Math.floor(1000 + Math.random() * 9000));
    state.signing[id] = { code, expiresAt };
    writeState(state);
    return { expiresAt, demoCode: code };
  },
  async confirmSigning(id, code) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'SIGN');
    const signing = state.signing[id];
    if (!signing || signing.code !== code || Date.parse(signing.expiresAt) <= Date.now())
      throw new ApiError('Код неверный или истёк. Запросите новый код.', 400, [
        { field: 'code', message: 'Проверьте код' },
      ]);
    delete state.signing[id];
    transition(state, app, 'SIGNED', 'CLIENT');
    transition(state, app, 'DISBURSED');
    return persist(state, app);
  },
  async getDocuments(id) {
    await wait();
    const { state } = application(id, user().role === 'UNDERWRITER');
    return structuredClone(state.documents[id] ?? []);
  },
  async uploadDocument(id, file, type) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'UPLOAD_DOCUMENT');
    if ((state.documents[id]?.length ?? 0) >= 20)
      throw new ApiError('К заявке можно загрузить не больше 20 документов.', 400);
    if (file.size > 10 * 1024 * 1024) throw new ApiError('Файл должен быть не больше 10 МБ.', 413);
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type))
      throw new ApiError('Поддерживаются PDF, JPG и PNG.', 400);
    const doc = {
      id: crypto.randomUUID(),
      documentType: type,
      fileName: file.name,
      mimeType: file.type,
      sizeBytes: file.size,
      uploadedAt: new Date().toISOString(),
    };
    (state.documents[id] ??= []).push(doc);
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 8192)
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
    state.documentContents[doc.id] = btoa(binary);
    writeState(state);
    return doc;
  },
  async getDocumentContent(id, documentId, backoffice = false) {
    await wait();
    const { state } = application(id, backoffice);
    const doc = state.documents[id]?.find((item) => item.id === documentId);
    const content = state.documentContents[documentId];
    if (!doc || content === undefined)
      throw new ApiError('Содержимое документа недоступно. Загрузите файл заново.', 404);
    return new Blob([Uint8Array.from(atob(content), (char) => char.charCodeAt(0))], {
      type: doc.mimeType,
    });
  },
  async getNotifications(unreadOnly = false, page = 0) {
    await wait();
    const current = user();
    if (current.role !== 'CLIENT') throw new ApiError('Уведомления доступны клиенту.', 403);
    const state = readState();
    state.applications.forEach((app) => progressApplication(state, app));
    writeState(state);
    return pageOf(
      state.notifications.filter(
        (item) =>
          (!unreadOnly || !item.read) &&
          state.applications.some(
            (app) =>
              app.id === item.applicationId &&
              current.companies?.some((owned) => owned.id === app.companyId),
          ),
      ),
      page,
    );
  },
  async markNotificationRead(id) {
    await wait();
    const current = user();
    const state = readState();
    const item = state.notifications.find((item) => item.id === id);
    const app = state.applications.find((app) => app.id === item?.applicationId);
    if (
      current.role !== 'CLIENT' ||
      !item ||
      !app ||
      !current.companies?.some((owned) => owned.id === app.companyId)
    )
      throw new ApiError('Уведомление не найдено.', 404);
    item.read = true;
    writeState(state);
  },
  async respondInfo(id, requestId, data) {
    await wait();
    const { state, app } = application(id);
    checkAction(app, 'RESPOND_INFO');
    const request = app.openInfoRequest;
    if (!request || request.id !== requestId)
      throw new ApiError('Запрос сведений уже закрыт.', 409);
    const missing = request.requestedFields.filter((path) =>
      path.startsWith('financials.')
        ? app.financials?.[path.slice(11) as keyof Financials] === undefined
        : Reflect.get(app, path) === undefined,
    );
    if (missing.length)
      throw new ApiError(
        'Заполните запрошенные поля.',
        400,
        missing.map((field) => ({ field, message: 'Заполните поле' })),
      );
    const docs = state.documents[id] ?? [];
    if (
      data.documentIds.some((docId) => !docs.some((doc) => doc.id === docId && !doc.infoRequestId))
    )
      throw new ApiError('Документ не принадлежит заявке или уже приложен к другому ответу.', 400);
    if (
      request.requestedDocuments.some(
        (type) =>
          !docs.some((doc) => doc.documentType === type && data.documentIds.includes(doc.id)),
      )
    )
      throw new ApiError('Загрузите все запрошенные документы.', 400);
    request.status = 'ANSWERED';
    request.response = data.comment;
    for (const doc of docs) if (data.documentIds.includes(doc.id)) doc.infoRequestId = request.id;
    const requests = (state.infoRequests[id] ??= []);
    if (!requests.some((item) => item.id === request.id)) requests.push(request);
    else state.infoRequests[id] = requests.map((item) => (item.id === request.id ? request : item));
    delete app.openInfoRequest;
    app.submittedAt = new Date().toISOString();
    transition(
      state,
      app,
      request.createdBy === 'EMPLOYEE' ? 'MANUAL_REVIEW' : 'ENRICHMENT',
      'CLIENT',
    );
    return persist(state, app);
  },
  async getBackofficeApplications(queue, page) {
    await wait();
    bank();
    const state = readState();
    state.applications.forEach((app) => progressApplication(state, app));
    writeState(state);
    return pageOf(
      state.applications
        .filter(
          (app) =>
            (queue === 'ALL' && app.status !== 'DRAFT') ||
            app.status === (queue === 'REVIEW' ? 'MANUAL_REVIEW' : 'PENDING_CONFIRMATION'),
        )
        .sort((a, b) =>
          queue === 'ALL'
            ? Date.parse(b.submittedAt ?? b.createdAt) - Date.parse(a.submittedAt ?? a.createdAt)
            : Date.parse(a.updatedAt) - Date.parse(b.updatedAt),
        )
        .map((app) => ({
          id: app.id,
          number: app.number,
          status: app.status,
          companyName: company(app.companyId, true).name,
          inn: company(app.companyId, true).inn,
          productCode: app.productCode,
          requestedAmount: app.requestedAmount,
          waitingSince: app.updatedAt,
          reasons: app.reasons,
        })),
      page,
    );
  },
  async getBackofficeApplication(id) {
    await wait();
    const { state, app } = application(id, true);
    writeState(state);
    return {
      application: structuredClone(app),
      company: structuredClone(company(app.companyId, true)),
      history: structuredClone(state.history[id] ?? []),
      infoRequests: structuredClone(
        state.infoRequests[id] ?? (app.openInfoRequest ? [app.openInfoRequest] : []),
      ),
      documents: structuredClone(state.documents[id] ?? []),
    };
  },
  async makeDecision(id, data) {
    await wait();
    const { state, app } = application(id, true);
    if (!['MANUAL_REVIEW', 'PENDING_CONFIRMATION'].includes(app.status))
      throw new ApiError('Заявка больше не ожидает решения.', 409);
    if (data.decision !== 'APPROVE' && !data.comment?.trim())
      throw new ApiError('Добавьте комментарий.', 400);
    const confirmation = app.status === 'PENDING_CONFIRMATION' && data.decision === 'APPROVE';
    if (
      confirmation &&
      ['productCode', 'amount', 'term', 'termUnit', 'rate'].some(
        (key) => Reflect.get(data, key) !== undefined,
      )
    )
      throw new ApiError('Подтверждение не должно менять принятые условия.', 400);
    if (data.decision === 'APPROVE' && data.productCode && data.productCode !== app.productCode)
      throw new ApiError('Для смены продукта выберите другие условия.', 400);
    if (data.decision === 'COUNTER_OFFER' && !data.amount)
      throw new ApiError('Укажите сумму предложения.', 400);
    if (data.decision === 'REJECT') {
      transition(state, app, 'REJECTED', 'EMPLOYEE');
      app.reasons = [{ code: 'MANUAL_REJECT', message: data.comment ?? '' }];
    } else if (confirmation) {
      if (app.offer) app.offer.preliminary = false;
      transition(state, app, 'OFFER_ACCEPTED', 'EMPLOYEE');
    } else {
      const proposed = {
        ...app,
        productCode: data.productCode ?? app.productCode,
        requestedAmount: data.amount ?? app.requestedAmount,
        term: data.term ?? app.term,
        termUnit: data.termUnit ?? app.termUnit,
      };
      const nextProduct = productFor(proposed);
      const baseRate =
        (app.subscription ? nextProduct.rateWithSubscription : undefined) ?? nextProduct.baseRate;
      const rate = data.rate ?? baseRate;
      const validation = schemaForProduct(nextProduct).safeParse(
        formData({
          ...proposed,
          fundingMode: nextProduct.fundingModes[0],
          repaymentType: nextProduct.repaymentTypes[0],
        }),
      );
      if (
        !validation.success ||
        !Number.isFinite(rate) ||
        rate < baseRate ||
        Math.round(rate * 100) !== rate * 100
      )
        throw new ApiError('Параметры предложения не соответствуют условиям продукта.', 400);
      app.offer = {
        ...offerFor(proposed, false, rate),
        preliminary: false,
      };
      app.reasons = data.comment
        ? [
            {
              code: data.decision === 'COUNTER_OFFER' ? 'MANUAL_COUNTER_OFFER' : 'MANUAL_DECISION',
              message: data.comment,
            },
          ]
        : [];
      transition(
        state,
        app,
        data.decision === 'APPROVE' ? 'APPROVED' : 'COUNTER_OFFER',
        'EMPLOYEE',
      );
    }
    if (data.comment) {
      const history = state.history[id]?.at(-1);
      if (history) history.comment = data.comment;
    }
    return persist(state, app);
  },
  async requestInfo(id, data) {
    await wait();
    const { state, app } = application(id, true);
    if (app.status !== 'MANUAL_REVIEW')
      throw new ApiError('Запрос сведений сейчас недоступен.', 409);
    if (
      data.requestedFields.some(
        (field) =>
          ![
            'purpose',
            'fundingMode',
            'repaymentType',
            'financials.annualRevenue',
            'financials.annualExpenses',
            'financials.bankMonthlyTurnover',
            'financials.monthlyDebtPayment',
          ].includes(field),
      )
    )
      throw new ApiError('Поле нельзя изменить после отправки заявки.', 400);
    app.openInfoRequest = {
      ...data,
      id: crypto.randomUUID(),
      createdBy: 'EMPLOYEE',
      createdAt: new Date().toISOString(),
      status: 'OPEN',
    };
    transition(state, app, 'NEED_INFO', 'EMPLOYEE');
    (state.infoRequests[id] ??= []).push(app.openInfoRequest);
    writeState(state);
    return structuredClone(app.openInfoRequest);
  },
  async setDemoDecision(id, status) {
    await wait();
    const { state, app } = application(id);
    decide(state, app, status);
    return persist(state, app);
  },
};
