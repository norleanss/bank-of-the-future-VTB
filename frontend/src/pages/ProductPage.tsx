import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { getWorkspace } from '../shared/lib/storage';
import { selectionSchema } from '../entities/credit/validation';
import { useCompany } from '../app/useCompany';
import { ProductActions } from '../features/application/ProductActions';
import { PaymentEstimate } from '../features/application/PaymentEstimate';
import { money } from '../shared/lib/format';
import { rateLabel, termRange } from '../shared/lib/creditTerms';
import { LoadingState, ErrorState } from '../shared/ui/Feedback';
import styles from './pages.module.css';

export function ProductPage() {
  const { id = '' } = useParams();
  const company = useCompany();
  const [workspace] = useState(() => getWorkspace(company?.id));
  const parsed = selectionSchema.safeParse(workspace.selection);
  const selection = parsed.success ? parsed.data : undefined;
  const query = useQuery({
    queryKey: ['products', id],
    queryFn: () => creditApi.getCreditProduct(id),
  });
  const matches = useQuery({
    queryKey: ['matches', company?.id, selection],
    enabled: !!selection,
    queryFn: () => creditApi.matchProducts(selection!),
  });
  if (query.isPending || (selection && matches.isPending)) return <LoadingState />;
  if (query.isError)
    return (
      <>
        <Link className="back" to="/credit-products">
          ← К продуктам
        </Link>
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      </>
    );
  if (selection && matches.isError)
    return <ErrorState error={matches.error} retry={() => void matches.refetch()} />;
  const product = query.data,
    match = selection
      ? matches.data?.items.find((item) => item.product.code === product.code)
      : undefined;
  return (
    <>
      <Link className="back" to="/credit-products">
        ← К продуктам
      </Link>
      <div className={styles.detailGrid}>
        <section>
          <div className="eyebrow">Финансирование бизнеса</div>
          <h1>{product.name}</h1>
          <p className="lead">{product.description}</p>
          <section className="panel">
            <h2>Условия продукта</h2>
            <dl className="summary">
              <div>
                <dt>Сумма / лимит</dt>
                <dd>
                  {money(product.minAmount)} — {money(product.maxAmount)}
                </dd>
              </div>
              <div>
                <dt>{product.termKind === 'LOAN' ? 'Срок кредита' : 'Срок одного транша'}</dt>
                <dd>{termRange(product.minTerm, product.maxTerm, product.termUnit)}</dd>
              </div>
              {product.maxTermWithSubscription && (
                <div>
                  <dt>С подпиской</dt>
                  <dd>
                    до {product.maxTermWithSubscription}{' '}
                    {product.termUnit === 'DAYS' ? 'дн.' : 'мес.'}
                  </dd>
                </div>
              )}
              {product.agreementTermMonths && (
                <div>
                  <dt>Срок договора</dt>
                  <dd>
                    {product.agreementTermMonths} мес.
                    {product.autoRenewal ? ', с автопродлением' : ''}
                  </dd>
                </div>
              )}
              <div>
                <dt>Возраст бизнеса</dt>
                <dd>
                  {product.minBusinessAgeMonths
                    ? `От ${product.minBusinessAgeMonths} месяцев`
                    : 'С первого дня регистрации'}
                </dd>
              </div>
            </dl>
          </section>
        </section>
        <aside className={`panel ${styles.offer}`}>
          <span className="badge">Базовая ставка</span>
          <h2>{rateLabel(product.baseRate, product.rateUnit)}</h2>
          {product.rateWithSubscription !== undefined && (
            <p className="muted">
              С подпиской — {rateLabel(product.rateWithSubscription, product.rateUnit)}
            </p>
          )}
          {product.subscriptionDescription && (
            <p className="muted">{product.subscriptionDescription}</p>
          )}
          {product.subscriptionFeePercent !== undefined && (
            <p>
              Комиссия за подписку: {product.subscriptionFeePercent}% от суммы лимита, единоразово
              при выдаче.
            </p>
          )}
          {match && (
            <div className="notice">
              <strong>
                {match.suitable
                  ? 'Подходит'
                  : match.requiresSubscription
                    ? 'Доступно только с подпиской'
                    : 'Измените параметры подбора'}
              </strong>
              {match.reasons.map((reason) => (
                <p key={reason}>{reason}</p>
              ))}
            </div>
          )}
          <ProductActions product={product} match={match} />
          <PaymentEstimate product={product} estimate={match?.estimate} />
          <p className="muted">Условия демонстрационные</p>
        </aside>
      </div>
    </>
  );
}
