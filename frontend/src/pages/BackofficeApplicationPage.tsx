import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { productsQuery } from '../app/queries';
import { ApplicationSummary } from '../entities/application/ApplicationSummary';
import { ApplicationStatusBadge } from '../entities/application/ApplicationStatusBadge';
import { UnderwriterForm, UnderwriterInfoRequest } from '../features/application/UnderwriterForm';
import { ApplicationHistory } from '../features/application/ApplicationHistory';
import { DocumentList } from '../features/application/DocumentList';
import { documentLabels, infoFieldLabels } from '../shared/lib/applicationFields';
import { termLabel, rateLabel } from '../shared/lib/creditTerms';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import { date, money } from '../shared/lib/format';
import styles from './pages.module.css';
export function BackofficeApplicationPage() {
  const { id = '' } = useParams();
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['backoffice', id],
    queryFn: () => creditApi.getBackofficeApplication(id),
    refetchInterval: 3000,
  });
  const products = useQuery(productsQuery);
  if (query.isPending || products.isPending) return <LoadingState />;
  if (query.isError || products.isError)
    return (
      <>
        <Link to="/backoffice" className="back">
          ← Очередь заявок
        </Link>
        <ErrorState
          error={query.error ?? products.error}
          retry={() => {
            void query.refetch();
            void products.refetch();
          }}
        />
      </>
    );
  const {
    application: app,
    company,
    history,
    enrichment,
    scoring,
    documents,
    infoRequests,
  } = query.data;
  const update = () => {
    void client.invalidateQueries({ queryKey: ['backoffice'] });
    void client.invalidateQueries({ queryKey: ['applications'] });
  };
  return (
    <>
      <Link to="/backoffice" className="back">
        ← Очередь заявок
      </Link>
      <div className="pageHeading">
        <h1>Заявка {app.number}</h1>
        <ApplicationStatusBadge status={app.status} />
      </div>
      <div className={styles.detailGrid}>
        <section className="fields">
          <article className="panel">
            <h2>{company.name}</h2>
            <p className="muted">
              ИНН {company.inn} · зарегистрирована {date(company.registrationDate)}
            </p>
            <ApplicationSummary
              data={app}
              productName={
                products.data.find((product) => product.code === app.productCode)?.name ??
                'Продукт не выбран'
              }
              financials
            />
          </article>
          <article className="panel">
            <h2>Проверки и оценка</h2>
            {enrichment ? (
              <>
                <p>
                  Налоговый долг:{' '}
                  {enrichment.taxDebtAmount ? money(enrichment.taxDebtAmount) : 'Нет данных'}
                </p>
                <p>Действующих кредитов: {enrichment.activeCreditsCount ?? 'Нет данных'}</p>
                <p>
                  Общая задолженность:{' '}
                  {enrichment.totalDebtAmount !== undefined
                    ? money(enrichment.totalDebtAmount)
                    : 'Нет данных'}
                </p>
                <p>
                  Просрочка:{' '}
                  {enrichment.maxOverdueDays12m === undefined
                    ? 'Нет данных'
                    : `${enrichment.maxOverdueDays12m} дн.`}
                </p>
                {enrichment.sources.map((source) => (
                  <p key={source.source}>
                    {
                      {
                        EGRUL: 'Регистрация компании',
                        FNS: 'Налоговые сведения',
                        BKI: 'Кредитная история',
                      }[source.source]
                    }
                    :{' '}
                    {
                      {
                        OK: 'Получены',
                        NOT_FOUND: 'Не найдены',
                        ERROR: 'Ошибка источника',
                        TIMEOUT: 'Источник не ответил',
                      }[source.status]
                    }
                  </p>
                ))}
              </>
            ) : (
              <p className="muted">Данные внешних проверок ещё не поступили.</p>
            )}
            {scoring ? (
              <>
                <p>
                  Скоринговый балл: <strong>{scoring.score} / 100</strong>
                  {scoring.grade ? ` · Класс ${scoring.grade}` : ''}
                </p>
                {scoring.factors.map((factor, index) => (
                  <p key={index}>
                    {factor.explanation ?? 'Фактор оценки'}: {factor.points}
                    {factor.maxPoints !== undefined ? ` / ${factor.maxPoints}` : ''}
                    {factor.inputValue !== undefined ? ` · Значение: ${factor.inputValue}` : ''}
                  </p>
                ))}
              </>
            ) : (
              <p className="muted">Скоринговая оценка не передана.</p>
            )}
            {app.reasons.map((reason) => (
              <p key={reason.code}>{reason.message}</p>
            ))}
          </article>
          <article className="panel">
            <h2>Документы</h2>
            <DocumentList id={id} documents={documents} backoffice />
          </article>
          <article className="panel">
            <h2>Запросы сведений и ответы</h2>
            {infoRequests.length ? (
              infoRequests.map((request) => (
                <section key={request.id}>
                  <h3>{request.status === 'OPEN' ? 'Ожидает ответа' : 'Клиент ответил'}</h3>
                  <p className="muted">
                    {date(request.createdAt)} ·{' '}
                    {request.createdBy === 'EMPLOYEE'
                      ? 'Запрос сотрудника'
                      : 'Автоматический запрос'}
                  </p>
                  <p>{request.message}</p>
                  {request.requestedFields.length > 0 && (
                    <p className="muted">
                      Поля:{' '}
                      {request.requestedFields
                        .map((field) => infoFieldLabels[field] ?? field)
                        .join('; ')}
                    </p>
                  )}
                  {request.requestedDocuments.length > 0 && (
                    <p>
                      Документы:{' '}
                      {request.requestedDocuments.map((type) => documentLabels[type]).join(', ')}
                    </p>
                  )}
                  {request.response && (
                    <p>
                      <strong>Ответ клиента:</strong> {request.response}
                    </p>
                  )}
                  {documents.some((doc) => doc.infoRequestId === request.id) && (
                    <DocumentList
                      id={id}
                      backoffice
                      documents={documents.filter((doc) => doc.infoRequestId === request.id)}
                    />
                  )}
                </section>
              ))
            ) : (
              <p className="muted">Банк не запрашивал дополнительные сведения.</p>
            )}
          </article>
          <article className="panel">
            <h2>История заявки</h2>
            <ApplicationHistory history={history} />
          </article>
        </section>
        <aside className="panel">
          {['MANUAL_REVIEW', 'PENDING_CONFIRMATION'].includes(app.status) ? (
            <>
              <h2>Решение по заявке</h2>
              {app.offer && (
                <dl className="summary">
                  <div>
                    <dt>Предложение банка</dt>
                    <dd>{app.offer.productName}</dd>
                  </div>
                  <div>
                    <dt>Сумма / лимит</dt>
                    <dd>{money(app.offer.amount)}</dd>
                  </div>
                  <div>
                    <dt>{app.offer.termKind === 'TRANCHE' ? 'Срок транша' : 'Срок кредита'}</dt>
                    <dd>{termLabel(app.offer.term, app.offer.termUnit)}</dd>
                  </div>
                  <div>
                    <dt>Ставка</dt>
                    <dd>{rateLabel(app.offer.rate, app.offer.rateUnit)}</dd>
                  </div>
                </dl>
              )}
              <UnderwriterForm key={app.updatedAt} application={app} onSuccess={update} />
              {app.status === 'MANUAL_REVIEW' && (
                <details style={{ marginTop: 24 }}>
                  <summary>Запросить дополнительные данные</summary>
                  <UnderwriterInfoRequest id={app.id} onSuccess={update} />
                </details>
              )}
            </>
          ) : (
            <>
              <h2>Действия не требуются</h2>
              <p>{app.nextStepHint ?? 'Заявка перешла на другой этап.'}</p>
            </>
          )}
        </aside>
      </div>
    </>
  );
}
