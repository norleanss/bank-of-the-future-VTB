import { useQuery } from '@tanstack/react-query';
import type { ApplicationCreateRequest } from '../../shared/types/models';
import { dictionariesQuery } from '../../app/queries';
import { money } from '../../shared/lib/format';
import { termLabel } from '../../shared/lib/creditTerms';
export function ApplicationSummary({
  data,
  productName,
  companyName,
  financials = false,
}: {
  data: ApplicationCreateRequest;
  productName: string;
  companyName?: string;
  financials?: boolean;
}) {
  const dictionaries = useQuery(dictionariesQuery);
  return (
    <dl className="summary">
      {companyName && (
        <div>
          <dt>Компания</dt>
          <dd>{companyName}</dd>
        </div>
      )}
      <div>
        <dt>Продукт</dt>
        <dd>{productName}</dd>
      </div>
      <div>
        <dt>Сумма / лимит</dt>
        <dd>{data.requestedAmount ? money(data.requestedAmount) : 'Не указана'}</dd>
      </div>
      <div>
        <dt>Срок кредита / транша</dt>
        <dd>{data.term && data.termUnit ? termLabel(data.term, data.termUnit) : 'Не указан'}</dd>
      </div>
      <div>
        <dt>Цель</dt>
        <dd>
          {dictionaries.data?.purposes.find((item) => item.code === data.purpose)?.label ??
            'Не указана'}
        </dd>
      </div>
      <div>
        <dt>Подписка</dt>
        <dd>{data.subscription ? 'Подключается' : 'Без подписки'}</dd>
      </div>
      {financials &&
        (
          [
            ['annualRevenue', 'Выручка за 12 месяцев'],
            ['annualExpenses', 'Расходы за 12 месяцев'],
            ['bankMonthlyTurnover', 'Оборот по счёту в месяц'],
            ['monthlyDebtPayment', 'Платежи по кредитам в месяц'],
          ] as const
        ).map(([field, label]) => (
          <div key={field}>
            <dt>{label}</dt>
            <dd>
              {data.financials?.[field] === undefined
                ? 'Не указано'
                : money(data.financials[field])}
            </dd>
          </div>
        ))}
    </dl>
  );
}
