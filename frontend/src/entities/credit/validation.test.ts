import { describe, it, expect } from 'vitest';
import { schemaForProduct, toApplicationRequest } from './validation';
import { products } from '../../shared/mocks/fixtures';
import { normalizeMoney, toKopecks } from '../../shared/lib/money';
import { money } from '../../shared/lib/format';
import type { ApplicationFormData } from '../../shared/types/models';
const data: ApplicationFormData = {
  companyId: 'company',
  productCode: 'OVERDRAFT',
  purpose: 'CASH_GAP',
  requestedAmount: '1500000.00',
  term: 75,
  termUnit: 'DAYS',
  fundingMode: 'ON_DEMAND',
  repaymentType: 'INTEREST_ONLY',
  subscription: true,
  annualRevenue: '12000000',
  annualExpenses: '8000000',
  bankMonthlyTurnover: '1000000',
  monthlyDebtPayment: '0',
};
describe('API 0.2 form validation', () => {
  it('requires account turnover for overdrafts and permits it to be omitted for loans', () => {
    const withoutTurnover = { ...data, bankMonthlyTurnover: '' };
    expect(
      schemaForProduct(products.find((item) => item.code === 'OVERDRAFT')).safeParse(
        withoutTurnover,
      ).success,
    ).toBe(false);
    expect(
      schemaForProduct(products.find((item) => item.code === 'LUMP_SUM_LOAN')).safeParse({
        ...withoutTurnover,
        productCode: 'LUMP_SUM_LOAN',
        term: 24,
        termUnit: 'MONTHS',
        fundingMode: 'LUMP_SUM',
        repaymentType: 'ANNUITY',
      }).success,
    ).toBe(true);
  });
  it('accepts an overdraft of 75 days only with subscription', () => {
    const schema = schemaForProduct(products.find((item) => item.code === 'OVERDRAFT'));
    expect(schema.safeParse(data).success).toBe(true);
    expect(schema.safeParse({ ...data, subscription: false }).success).toBe(false);
    expect(schema.safeParse({ ...data, term: 91 }).success).toBe(false);
  });
  it('keeps precise decimal strings at the API boundary', () => {
    const request = toApplicationRequest({
      ...data,
      requestedAmount: '1 500 000,05',
    });
    expect(request.requestedAmount).toBe('1500000.05');
    expect(request.financials?.annualRevenue).toBe('12000000.00');
    expect(toKopecks('999999999999999.99')).toBe(99999999999999999n);
    expect(money('999999999999999.99').replace(/\s/g, '')).toBe('999999999999999,99₽');
    expect(normalizeMoney('0,01')).toBe('0.01');
  });
  it('allows empty financials only for Gamma-Start', () => {
    const values = {
      ...data,
      productCode: 'GAMMA_START',
      requestedAmount: '500000.00',
      term: 6,
      termUnit: 'MONTHS',
      fundingMode: 'TRANCHES',
      annualRevenue: '',
      annualExpenses: '',
      bankMonthlyTurnover: '',
      monthlyDebtPayment: '',
    };
    expect(
      schemaForProduct(products.find((item) => item.code === 'GAMMA_START')).safeParse(values)
        .success,
    ).toBe(true);
    expect(
      schemaForProduct(products.find((item) => item.code === 'OVERDRAFT')).safeParse({
        ...data,
        annualRevenue: '',
      }).success,
    ).toBe(false);
  });
});
