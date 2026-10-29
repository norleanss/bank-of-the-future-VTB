import { describe, expect, it } from 'vitest';
import { matchProducts } from './matchProducts';
import type { SelectionData } from '../types/models';

const selection: SelectionData = {
  companyId: 'company',
  purpose: 'WORKING_CAPITAL',
  requestedAmount: '500000.00',
  term: 2,
  termUnit: 'MONTHS',
  fundingMode: 'ON_DEMAND',
};

describe('product matching', () => {
  it('uses purpose to rank suitable products without excluding them', () => {
    const workingCapital = matchProducts(selection, 24);
    const equipment = matchProducts({ ...selection, purpose: 'EQUIPMENT' }, 24);
    const cashGap = matchProducts({ ...selection, purpose: 'CASH_GAP' }, 24);
    expect(workingCapital.map((item) => item.product.code)).toEqual([
      'CREDIT_LINE',
      'OVERDRAFT',
      'GAMMA_START',
      'LUMP_SUM_LOAN',
    ]);
    expect(equipment.filter((item) => item.suitable).map((item) => item.product.code)).toEqual([
      'CREDIT_LINE',
      'OVERDRAFT',
      'GAMMA_START',
    ]);
    expect(cashGap[0]).toMatchObject({
      product: { code: 'OVERDRAFT' },
      suitable: true,
      recommended: true,
    });
    expect(cashGap.filter((item) => item.recommended)).toHaveLength(1);
  });
  it('puts Gamma-Start first for young businesses and respects the age boundary', () => {
    const young = matchProducts(selection, 11);
    expect(young[0]).toMatchObject({ product: { code: 'GAMMA_START' }, recommended: true });
    expect(young.find((item) => item.product.code === 'CREDIT_LINE')?.suitable).toBe(false);
    expect(matchProducts(selection, 12)[0]?.product.code).toBe('CREDIT_LINE');
  });
  it('compares day and month terms and recommends subscription-only products', () => {
    const items = matchProducts({ ...selection, purpose: 'CASH_GAP', term: 3 }, 24);
    expect(items[0]).toMatchObject({
      product: { code: 'OVERDRAFT' },
      suitable: false,
      requiresSubscription: true,
      recommended: true,
    });
    expect(items[0]?.reasons.join(' ')).toContain('90 дн.');
    expect(items[0]?.reasons.join(' ')).toContain('комиссия 1%');
    expect(items[0]?.estimate?.monthlyPayment).toBeUndefined();
    expect(Number(items[0]?.estimate?.monthlyPaymentWithSubscription)).toBeGreaterThan(0);
    expect(items[0]?.estimate?.subscriptionFee).toBe('5000.00');
    const tooLong = matchProducts({ ...selection, term: 91, termUnit: 'DAYS' }, 24);
    expect(tooLong.find((item) => item.product.code === 'OVERDRAFT')).toMatchObject({
      suitable: false,
      requiresSubscription: false,
    });
  });
  it('retains the term reason when subscription cannot fix another mismatch', () => {
    const items = matchProducts({ ...selection, requestedAmount: '30000001.00', term: 3 }, 24);
    const overdraft = items.find((item) => item.product.code === 'OVERDRAFT');
    expect(overdraft).toMatchObject({ suitable: false, requiresSubscription: false });
    expect(overdraft?.estimate).toBeUndefined();
    expect(overdraft?.reasons).toHaveLength(2);
    expect(overdraft?.reasons.join(' ')).toContain('с подпиской');
    expect(items.some((item) => item.recommended)).toBe(false);
  });
});
