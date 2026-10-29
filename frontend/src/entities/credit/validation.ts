import { z } from 'zod';
import type {
  ApplicationCreateRequest,
  ApplicationFormData,
  CreditProduct,
} from '../../shared/types/models';
import { normalizeMoney, toKopecks } from '../../shared/lib/money';

const amount = z
  .string()
  .transform(normalizeMoney)
  .refine(
    (value) => /^\d{1,15}\.\d{2}$/.test(value),
    'Введите сумму: до 15 цифр и не больше двух знаков после запятой',
  );
const positiveAmount = amount.refine(
  (value) => /^\d{1,15}\.\d{2}$/.test(value) && toKopecks(value) > 0n,
  'Сумма должна быть больше нуля',
);
export const selectionSchema = z.object({
  companyId: z.string().min(1, 'Выберите компанию'),
  purpose: z.enum(['WORKING_CAPITAL', 'EQUIPMENT', 'CASH_GAP', 'REFINANCING', 'STARTUP']),
  requestedAmount: positiveAmount,
  term: z
    .number()
    .int('Укажите целое число')
    .min(1, 'Срок должен быть больше нуля')
    .max(1800, 'Проверьте срок'),
  termUnit: z.enum(['MONTHS', 'DAYS']),
  fundingMode: z.enum(['LUMP_SUM', 'TRANCHES', 'ON_DEMAND']),
});
const optionalAmount = z.union([z.literal(''), amount]);
export function schemaForProduct(product?: CreditProduct) {
  return selectionSchema
    .extend({
      productCode: z.enum(['LUMP_SUM_LOAN', 'CREDIT_LINE', 'OVERDRAFT', 'GAMMA_START']),
      repaymentType: z.enum(['ANNUITY', 'INTEREST_ONLY']),
      subscription: z.boolean(),
      annualRevenue: optionalAmount,
      annualExpenses: optionalAmount,
      bankMonthlyTurnover: optionalAmount,
      monthlyDebtPayment: optionalAmount,
    })
    .superRefine((data, ctx) => {
      if (product) {
        if (data.productCode !== product.code)
          ctx.addIssue({
            code: 'custom',
            path: ['productCode'],
            message: 'Выберите доступный продукт',
          });
        const normalized = normalizeMoney(data.requestedAmount);
        if (
          /^\d{1,15}\.\d{2}$/.test(normalized) &&
          (toKopecks(normalized) < toKopecks(product.minAmount) ||
            toKopecks(normalized) > toKopecks(product.maxAmount))
        )
          ctx.addIssue({
            code: 'custom',
            path: ['requestedAmount'],
            message: 'Сумма вне диапазона продукта',
          });
        const days = data.term * (data.termUnit === 'MONTHS' ? 30 : 1);
        const factor = product.termUnit === 'MONTHS' ? 30 : 1;
        const max = data.subscription
          ? (product.maxTermWithSubscription ?? product.maxTerm)
          : product.maxTerm;
        if (days < product.minTerm * factor || days > max * factor)
          ctx.addIssue({
            code: 'custom',
            path: ['term'],
            message: 'Срок не соответствует выбранным условиям',
          });
        if (!product.fundingModes.includes(data.fundingMode))
          ctx.addIssue({
            code: 'custom',
            path: ['fundingMode'],
            message: 'Способ выдачи недоступен для продукта',
          });
        if (!product.repaymentTypes.includes(data.repaymentType))
          ctx.addIssue({
            code: 'custom',
            path: ['repaymentType'],
            message: 'Выберите доступную схему погашения',
          });
        if (data.subscription && product.rateWithSubscription === undefined)
          ctx.addIssue({
            code: 'custom',
            path: ['subscription'],
            message: 'Подписка недоступна',
          });
      }
      if (data.productCode !== 'GAMMA_START') {
        for (const field of [
          'annualRevenue',
          'annualExpenses',
          'bankMonthlyTurnover',
          'monthlyDebtPayment',
        ] as const)
          if (
            data[field] === '' &&
            (field !== 'bankMonthlyTurnover' || data.productCode === 'OVERDRAFT')
          )
            ctx.addIssue({
              code: 'custom',
              path: [field],
              message: 'Заполните поле; если отсутствует — укажите 0',
            });
      }
    });
}
export function toApplicationRequest(data: ApplicationFormData): ApplicationCreateRequest {
  const financials = Object.fromEntries(
    (['annualRevenue', 'annualExpenses', 'bankMonthlyTurnover', 'monthlyDebtPayment'] as const)
      .filter((field) => data[field] !== '')
      .map((field) => [field, normalizeMoney(data[field])]),
  );
  return {
    companyId: data.companyId,
    productCode: data.productCode,
    purpose: data.purpose,
    requestedAmount: normalizeMoney(data.requestedAmount),
    term: data.term,
    termUnit: data.termUnit,
    fundingMode: data.fundingMode,
    repaymentType: data.repaymentType,
    subscription: data.subscription,
    collateral: false,
    financials,
  };
}
