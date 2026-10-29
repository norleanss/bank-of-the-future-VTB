import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { ApplicationStatusBadge } from '../entities/application/ApplicationStatusBadge';
import { dateTime, money } from '../shared/lib/format';
import { LoadingState, ErrorState } from '../shared/ui/Feedback';
import { Button } from '../shared/ui/Button';
import { Tabs } from '../shared/ui/Tabs';
export function BackofficePage() {
  const [queue, setQueue] = useState<'REVIEW' | 'CONFIRMATION' | 'ALL'>('REVIEW');
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['backoffice', queue, page],
    queryFn: () => creditApi.getBackofficeApplications(queue, page),
    refetchInterval: 3000,
  });
  return (
    <>
      <div className="eyebrow">Кабинет сотрудника банка</div>
      <h1>Очередь заявок</h1>
      <p className="lead">Рассмотрите заявки и подтвердите предварительные условия.</p>
      <Tabs
        label="Очереди заявок"
        tabs={[
          { id: 'REVIEW', title: 'На рассмотрении' },
          { id: 'CONFIRMATION', title: 'Подтверждение условий' },
          { id: 'ALL', title: 'Все заявки' },
        ]}
        selected={queue}
        onChange={(id) => {
          setQueue(id);
          setPage(0);
        }}
      />
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          {query.data.content.length ? (
            <div className="fields">
              {query.data.content.map((app) => (
                <article className="panel" key={app.id}>
                  <Link to={`/backoffice/applications/${app.id}`}>
                    <h2>
                      {app.number} · {app.companyName}
                    </h2>
                  </Link>
                  <p className="muted">
                    ИНН {app.inn}
                    {app.requestedAmount ? ` · ${money(app.requestedAmount)}` : ''}
                  </p>
                  <ApplicationStatusBadge status={app.status} />
                  {app.waitingSince && (
                    <p className="muted">Ожидает с {dateTime(app.waitingSince)}</p>
                  )}
                  {app.score !== undefined && (
                    <p>
                      Скоринговый балл: {app.score} / 100{app.grade ? ` · Класс ${app.grade}` : ''}
                    </p>
                  )}
                  {app.reasons?.map((reason) => (
                    <p key={reason.code}>{reason.message}</p>
                  ))}
                </article>
              ))}
            </div>
          ) : (
            <section className="panel">
              <h2>В очереди пока нет заявок</h2>
              <p>Новые заявки появятся автоматически.</p>
            </section>
          )}
          {query.data.totalPages > 1 && (
            <div className="actions">
              <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
                Предыдущая страница
              </Button>
              <span>
                Страница {page + 1} из {Math.max(1, query.data.totalPages)}
              </span>
              <Button
                disabled={page + 1 >= query.data.totalPages}
                onClick={() => setPage(page + 1)}
              >
                Следующая страница
              </Button>
            </div>
          )}
        </>
      )}
    </>
  );
}
