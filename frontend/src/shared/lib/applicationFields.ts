import type { DocumentType } from '../types/models';

export const documentLabels: Record<DocumentType, string> = {
  FINANCIAL_STATEMENT: 'Финансовая отчётность',
  BANK_STATEMENT: 'Выписка по счёту',
  CHARTER: 'Устав',
  OTHER: 'Другой документ',
};

export const infoFieldLabels: Record<string, string> = {
  'financials.annualRevenue': 'Выручка за 12 месяцев, ₽',
  'financials.annualExpenses': 'Расходы за 12 месяцев, ₽',
  'financials.bankMonthlyTurnover': 'Оборот по счёту в Гамма-Банке в месяц, ₽',
  'financials.monthlyDebtPayment': 'Платежи по кредитам в месяц, ₽',
  purpose: 'Цель кредита',
  fundingMode: 'Способ получения денег',
  repaymentType: 'Способ погашения',
};
