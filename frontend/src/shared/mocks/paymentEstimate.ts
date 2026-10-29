import type { CreditProduct, PaymentEstimate, SelectionData } from '../types/models';

// Demo only. In HTTP mode the backend supplies both estimates and final offer payments.
export function paymentEstimate(
  product: CreditProduct,
  data: Pick<SelectionData, 'requestedAmount' | 'term' | 'termUnit'>,
  requiresSubscription = false,
): PaymentEstimate {
  const amount = Number(data.requestedAmount);
  const months = data.termUnit === 'DAYS' ? data.term / 30 : data.term;
  const payment = (rate: number) => {
    const monthlyRate = product.rateUnit === 'PERCENT_PER_DAY' ? (rate * 30) / 100 : rate / 1200;
    return (
      product.termKind === 'TRANCHE' || product.repaymentTypes[0] === 'INTEREST_ONLY'
        ? amount * monthlyRate
        : monthlyRate === 0
          ? amount / months
          : (amount * monthlyRate) / (1 - (1 + monthlyRate) ** -months)
    ).toFixed(2);
  };
  return {
    repaymentType: product.repaymentTypes[0]!,
    ...(!requiresSubscription ? { monthlyPayment: payment(product.baseRate) } : {}),
    ...(product.rateWithSubscription !== undefined
      ? {
          monthlyPaymentWithSubscription: payment(product.rateWithSubscription),
          ...(product.subscriptionFeePercent !== undefined
            ? {
                subscriptionFee: ((amount * product.subscriptionFeePercent) / 100).toFixed(2),
              }
            : {}),
        }
      : {}),
  };
}
