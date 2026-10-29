import type { CreditProduct, PaymentEstimate as Estimate } from '../../shared/types/models';
import { money } from '../../shared/lib/format';

export function PaymentEstimate({
  product,
  estimate,
}: {
  product: CreditProduct;
  estimate?: Estimate;
}) {
  if (!estimate) return null;
  return (
    <section aria-label="Предварительный расчёт платежа">
      <h3>{product.termKind === 'TRANCHE' ? 'Проценты в месяц' : 'Платёж в месяц'}</h3>
      <dl className="summary">
        {estimate.monthlyPayment !== undefined && (
          <div>
            <dt>Без подписки</dt>
            <dd>{money(estimate.monthlyPayment)}</dd>
          </div>
        )}
        {estimate.monthlyPaymentWithSubscription !== undefined && (
          <div>
            <dt>С подпиской</dt>
            <dd>{money(estimate.monthlyPaymentWithSubscription)}</dd>
          </div>
        )}
        {estimate.subscriptionFee !== undefined && (
          <div>
            <dt>Комиссия за подписку при выдаче</dt>
            <dd>{money(estimate.subscriptionFee)}</dd>
          </div>
        )}
      </dl>
      <p className="muted">
        Предварительный расчёт
        {product.termKind === 'TRANCHE' ? ' при полном использовании лимита' : ''}. Комиссия
        оплачивается отдельно. Итоговый платёж — в предложении банка.
      </p>
    </section>
  );
}
