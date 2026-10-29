import type { ProductCode, ProductMatch, Purpose, SelectionData } from '../types/models';
import { termLabel, rateLabel } from '../lib/creditTerms';
import { money } from '../lib/format';
import { toKopecks } from '../lib/money';
import { products } from './fixtures';
import { paymentEstimate } from './paymentEstimate';

const priorities: Record<Purpose, ProductCode[]> = {
  WORKING_CAPITAL: ['CREDIT_LINE', 'LUMP_SUM_LOAN', 'OVERDRAFT'],
  EQUIPMENT: ['LUMP_SUM_LOAN', 'CREDIT_LINE'],
  CASH_GAP: ['OVERDRAFT', 'CREDIT_LINE'],
  REFINANCING: ['LUMP_SUM_LOAN'],
  STARTUP: ['GAMMA_START'],
};

export function matchProducts(data: SelectionData, businessAgeMonths: number): ProductMatch[] {
  const priority = [...priorities[data.purpose]];
  if (businessAgeMonths < 12) {
    const startIndex = priority.indexOf('GAMMA_START');
    if (startIndex >= 0) priority.splice(startIndex, 1);
    priority.unshift('GAMMA_START');
  }
  const amount = toKopecks(data.requestedAmount);
  const days = data.term * (data.termUnit === 'MONTHS' ? 30 : 1);
  const items = products.map((product): ProductMatch => {
    const reasons: string[] = [];
    const factor = product.termUnit === 'MONTHS' ? 30 : 1;
    const offersSubscription = product.rateWithSubscription !== undefined;
    const maxTerm = offersSubscription
      ? (product.maxTermWithSubscription ?? product.maxTerm)
      : product.maxTerm;
    if (amount < toKopecks(product.minAmount))
      reasons.push(`Минимальная сумма — ${money(product.minAmount)}.`);
    if (amount > toKopecks(product.maxAmount))
      reasons.push(`Максимальная сумма — ${money(product.maxAmount)}.`);
    const termKind = product.termKind === 'LOAN' ? 'кредита' : 'транша';
    const longerTerm = days > product.maxTerm * factor && days <= maxTerm * factor;
    if (days < product.minTerm * factor)
      reasons.push(
        `Минимальный срок ${termKind} — ${termLabel(product.minTerm, product.termUnit)}.`,
      );
    if (days > maxTerm * factor || longerTerm)
      reasons.push(
        `Максимальный срок ${termKind} — ${termLabel(product.maxTerm, product.termUnit)}${
          maxTerm > product.maxTerm ? `, с подпиской — ${termLabel(maxTerm, product.termUnit)}` : ''
        }.`,
      );
    const termReasonIndex = reasons.length - 1;
    if (!product.fundingModes.includes(data.fundingMode))
      reasons.push('Способ выдачи не соответствует продукту.');
    if (businessAgeMonths < product.minBusinessAgeMonths)
      reasons.push(
        `Минимальный возраст бизнеса — ${termLabel(product.minBusinessAgeMonths, 'MONTHS')}.`,
      );
    const requiresSubscription = longerTerm && reasons.length === 1;
    if (requiresSubscription && product.rateWithSubscription !== undefined)
      reasons[termReasonIndex] =
        `Выбранный срок доступен с подпиской: до ${termLabel(maxTerm, product.termUnit)}, ` +
        `ставка ${rateLabel(product.rateWithSubscription, product.rateUnit)} вместо ` +
        `${rateLabel(product.baseRate, product.rateUnit)}${
          product.subscriptionFeePercent === undefined
            ? '.'
            : `, комиссия ${product.subscriptionFeePercent}% от суммы.`
        }`;
    return {
      product: { code: product.code, name: product.name },
      suitable: reasons.length === 0,
      requiresSubscription,
      recommended: false,
      reasons,
      ...(reasons.length === 0 || requiresSubscription
        ? { estimate: paymentEstimate(product, data, requiresSubscription) }
        : {}),
    };
  });
  const fits = (item: ProductMatch) => item.suitable || item.requiresSubscription;
  const rank = (code: ProductCode) => {
    const index = priority.indexOf(code);
    return index < 0 ? priority.length : index;
  };
  items.sort(
    (a, b) =>
      Number(fits(b)) - Number(fits(a)) ||
      (fits(a) && fits(b) ? rank(a.product.code) - rank(b.product.code) : 0),
  );
  if (items[0] && fits(items[0])) items[0].recommended = true;
  return items;
}
