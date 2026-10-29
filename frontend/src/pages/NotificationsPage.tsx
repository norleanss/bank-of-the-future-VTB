import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { dateTime } from '../shared/lib/format';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import { Button } from '../shared/ui/Button';
import { Tabs } from '../shared/ui/Tabs';

export function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(0);
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['notifications', 'list', unreadOnly, page],
    queryFn: () => creditApi.getNotifications(unreadOnly, page),
    refetchInterval: 10_000,
  });
  const read = useMutation({
    mutationFn: (id: string) => creditApi.markNotificationRead(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['notifications'] });
      void client.invalidateQueries({ queryKey: ['applications', 'list'] });
    },
  });
  return (
    <>
      <div className="pageHeading">
        <h1>Уведомления</h1>
        <p className="lead">Решения банка и изменения по всем вашим компаниям.</p>
      </div>
      <Tabs
        label="Фильтр уведомлений"
        tabs={[
          { id: 'all', title: 'Все уведомления' },
          { id: 'unread', title: 'Непрочитанные' },
        ]}
        selected={unreadOnly ? 'unread' : 'all'}
        onChange={(id) => {
          setUnreadOnly(id === 'unread');
          setPage(0);
        }}
      />
      {read.isError && <ErrorState error={read.error} />}
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          {query.data.content.length ? (
            <div className="fields">
              {query.data.content.map((item) => (
                <article className="panel" key={item.id}>
                  <p className="muted">
                    {dateTime(item.createdAt)}
                    {!item.read && ' · Новое'}
                  </p>
                  <h2>{item.title}</h2>
                  <p>{item.body}</p>
                  <div className="actions">
                    {item.applicationId && (
                      <Link
                        to={`/applications/${item.applicationId}`}
                        onClick={() => {
                          if (!item.read) read.mutate(item.id);
                        }}
                      >
                        Открыть заявку
                      </Link>
                    )}
                    {!item.read && (
                      <Button
                        size={40}
                        loading={read.isPending && read.variables === item.id}
                        disabled={read.isPending}
                        onClick={() => read.mutate(item.id)}
                      >
                        Отметить прочитанным
                      </Button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <section className="panel">
              <h2>{unreadOnly ? 'Всё прочитано' : 'Уведомлений пока нет'}</h2>
              <p>Здесь появятся решения банка и запросы по вашим заявкам.</p>
            </section>
          )}
          {query.data.totalPages > 1 && (
            <div className="actions">
              <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
                Предыдущая страница
              </Button>
              <span>
                Страница {page + 1} из {query.data.totalPages}
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
