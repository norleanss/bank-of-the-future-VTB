import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { getWorkspace } from '../shared/lib/storage';
import { selectionSchema } from '../entities/credit/validation';
import { productsQuery } from '../app/queries';
import { useCompany } from '../app/useCompany';
import { ProductActions } from '../features/application/ProductActions';
import { PaymentEstimate } from '../features/application/PaymentEstimate';
import { rateLabel, termRange } from '../shared/lib/creditTerms';
import { money } from '../shared/lib/format';
import { LoadingState, ErrorState } from '../shared/ui/Feedback';
import styles from './pages.module.css';

export function ProductsPage() {
  const company = useCompany();
  const [workspace] = useState(() => getWorkspace(company?.id));
  const [params] = useSearchParams();
  const parsed = selectionSchema.safeParse(workspace.selection);
  const selection = parsed.success && params.get('all') !== '1' ? parsed.data : undefined;
  const products = useQuery(productsQuery);
  const matches = useQuery({
    queryKey: ['matches', company?.id, selection],
    enabled: !!selection,
    queryFn: () => creditApi.matchProducts(selection!),
  });
  if (products.isPending || (selection && matches.isPending))
    return <LoadingState text="Подбираем продукты для бизнеса…" />;
  if (products.isError)
    return <ErrorState error={products.error} retry={() => void products.refetch()} />;
  if (selection && matches.isError)
    return <ErrorState error={matches.error} retry={() => void matches.refetch()} />;
  const count =
    matches.data?.items.filter((item) => item.suitable || item.requiresSubscription).length ?? 0;
  const matchOrder = new Map(matches.data?.items.map((item, index) => [item.product.code, index]));
  const sorted = selection
    ? [...products.data].sort(
        (a, b) =>
          (matchOrder.get(a.code) ?? Number.MAX_SAFE_INTEGER) -
          (matchOrder.get(b.code) ?? Number.MAX_SAFE_INTEGER),
      )
    : products.data;
  return (
    <>
      <Link className="back" to="/credit-selection">
        ← Изменить параметры
      </Link>
      <div className="pageHeading">
        <div className="eyebrow">{company?.name}</div>
        <h1>
          {selection
            ? count
              ? `Мы подобрали для вас ${count} ${count === 1 ? 'продукт' : 'продукта'}`
              : 'Подходящие продукты не найдены'
            : 'Кредиты для бизнеса'}
        </h1>
        <p className="lead">Сравните базовые условия и предложение с подпиской.</p>
        {selection && <Link to="/credit-products?all=1">Все продукты каталога</Link>}
      </div>
      {sorted.length ? (
        <div className={styles.productGrid}>
          {sorted.map((product) => {
            const match = selection
              ? matches.data?.items.find((item) => item.product.code === product.code)
              : undefined;
            return (
              <article className={`panel ${styles.productCard}`} key={product.code}>
                <span className="badge">
                  {match?.recommended
                    ? 'Рекомендуем'
                    : product.termKind === 'LOAN'
                      ? 'Деньги одной суммой'
                      : 'Возобновляемый лимит'}
                </span>
                <h2>{product.name}</h2>
                <p className="muted">{product.description}</p>
                <dl className={styles.productStats}>
                  <div>
                    <dt>Сумма</dt>
                    <dd>до {money(product.maxAmount)}</dd>
                  </div>
                  <div>
                    <dt>{product.termKind === 'LOAN' ? 'Срок кредита' : 'Срок транша'}</dt>
                    <dd>{termRange(product.minTerm, product.maxTerm, product.termUnit)}</dd>
                  </div>
                  <div>
                    <dt>Базовая ставка</dt>
                    <dd>{rateLabel(product.baseRate, product.rateUnit)}</dd>
                  </div>
                </dl>
                {product.rateWithSubscription !== undefined && (
                  <p className={styles.productDiscount}>
                    С подпиской — {rateLabel(product.rateWithSubscription, product.rateUnit)}.
                    <br />
                    {product.subscriptionDescription}
                  </p>
                )}
                {match && (
                  <div className="notice">
                    <strong>
                      {match.suitable
                        ? 'Подходит на базовых условиях'
                        : match.requiresSubscription
                          ? 'Подходит только с подпиской'
                          : 'Не подходит по выбранным параметрам'}
                    </strong>
                    {match.reasons.map((reason) => (
                      <p key={reason}>{reason}</p>
                    ))}
                  </div>
                )}
                <Link to={`/credit-products/${product.code}`}>Подробнее о продукте</Link>
                <PaymentEstimate product={product} estimate={match?.estimate} />
                <p className="muted">Условия демонстрационные</p>
                <ProductActions product={product} match={match} />
              </article>
            );
          })}
        </div>
      ) : (
        <section className="panel">
          <h2>Каталог пока пуст</h2>
          <p>Попробуйте вернуться позже.</p>
        </section>
      )}
      <p className={styles.disclaimer}>
        Подбор предварительный. Решение, лимит и окончательные условия определит банк.
      </p>
    </>
  );
}
