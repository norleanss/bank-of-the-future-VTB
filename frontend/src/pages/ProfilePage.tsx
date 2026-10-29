import { useState } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { sessionQuery } from '../app/queries';
import { useCompany } from '../app/useCompany';
import { getWorkspace } from '../shared/lib/storage';
import { isCompleted } from '../entities/application/status';
import { ApplicationList } from '../entities/application/ApplicationList';
import { LoadingState, ErrorState } from '../shared/ui/Feedback';
import styles from './pages.module.css';
import { Button } from '../shared/ui/Button';
import { Checkbox } from '../shared/ui/Choice';
import { Tabs } from '../shared/ui/Tabs';

export function ProfilePage() {
  const { data: session } = useQuery(sessionQuery);
  const company = useCompany();
  const [allCompanies, setAllCompanies] = useState(false);
  const [tab, setTab] = useState<'active' | 'completed'>('active');
  const query = useInfiniteQuery({
    queryKey: ['applications', 'list', allCompanies ? 'all' : company?.id],
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      creditApi.getApplications(allCompanies ? undefined : company?.id, pageParam),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    refetchInterval: 3000,
  });
  const apps = query.data?.pages.flatMap((page) => page.content) ?? [];
  const visible = apps.filter((app) => isCompleted(app.status) === (tab === 'completed'));
  const workspace = getWorkspace(company?.id);
  return (
    <>
      <div className="eyebrow">Личный кабинет</div>
      <div className={styles.profileInfo}>
        <div className={styles.avatar} aria-hidden="true">
          И
        </div>
        <div>
          <h1>{session?.user.fullName}</h1>
          <span className="muted">
            {company?.name} · ИНН {company?.inn}
          </span>
        </div>
      </div>
      <section className="panel">
        <div className={styles.sectionHeading}>
          <h2>Заявки на кредит</h2>
          <Button size={40} href="/credit-selection">
            Новая заявка
          </Button>
        </div>
        {Object.keys(workspace.applicationDraft).length > 0 && (
          <div className="notice">
            <p>Есть незавершённый черновик этой компании.</p>
            <Button size={40} href="/applications/new">
              Продолжить заполнение
            </Button>
          </div>
        )}
        <Checkbox
          label="Показать заявки всех компаний"
          checked={allCompanies}
          onChange={setAllCompanies}
        />
        <Tabs
          label="Статус заявок"
          tabs={[
            { id: 'active', title: 'Активные' },
            { id: 'completed', title: 'Завершённые' },
          ]}
          selected={tab}
          onChange={setTab}
        />
        {query.isPending ? (
          <LoadingState />
        ) : query.isError ? (
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        ) : visible.length ? (
          <ApplicationList applications={visible} />
        ) : (
          <div className={styles.empty}>
            <div>
              <h3>
                {tab === 'active' ? 'Активных заявок пока нет' : 'Завершённых заявок пока нет'}
              </h3>
              <p>
                {query.hasNextPage
                  ? 'На загруженных страницах заявок этой группы нет. Загрузите ещё.'
                  : tab === 'active'
                    ? 'Начните с подбора кредита.'
                    : 'Здесь появятся выданные, отклонённые и закрытые заявки.'}
              </p>
            </div>
          </div>
        )}
        {query.hasNextPage && (
          <Button loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            Загрузить ещё заявки
          </Button>
        )}
      </section>
    </>
  );
}
