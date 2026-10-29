import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { productsQuery, companyQuery } from '../app/queries';
import { isCompleted } from '../entities/application/status';
import { ApplicationStatusBadge } from '../entities/application/ApplicationStatusBadge';
import { ApplicationSummary } from '../entities/application/ApplicationSummary';
import { DecisionPanel } from '../features/application/DecisionPanel';
import { DemoDecision } from '../features/application/DemoDecision';
import { StatusTimeline } from '../features/application/StatusTimeline';
import { ApplicationHistory } from '../features/application/ApplicationHistory';
import { DocumentList } from '../features/application/DocumentList';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import { date } from '../shared/lib/format';
import styles from './pages.module.css';
export function ApplicationPage() {
  const { id = '' } = useParams();
  const query = useQuery({
    queryKey: ['applications', id],
    queryFn: () => creditApi.getApplication(id),
    refetchInterval: (q) => (q.state.data && !isCompleted(q.state.data.status) ? 2500 : false),
  });
  const company = useQuery(companyQuery(query.data?.companyId ?? ''));
  const products = useQuery(productsQuery);
  const history = useQuery({
    queryKey: ['applications', id, 'history', query.data?.updatedAt],
    queryFn: () => creditApi.getApplicationHistory(id),
    enabled: !!query.data,
  });
  const documents = useQuery({
    queryKey: ['documents', id, query.data?.updatedAt],
    queryFn: () => creditApi.getDocuments(id),
    enabled: !!query.data,
  });
  if (query.isPending) return <LoadingState />;
  if (!query.data)
    return (
      <>
        <Link className="back" to="/profile">
          ← В личный кабинет
        </Link>
        <ErrorState error={query.error} retry={() => void query.refetch()} />
      </>
    );
  const app = query.data;
  return (
    <>
      <Link className="back" to="/profile">
        ← Все заявки
      </Link>
      <div className="pageHeading">
        <div className="eyebrow">
          От {date(app.submittedAt ?? app.createdAt)} · {company.data?.name}
        </div>
        <h1>Заявка {app.number}</h1>
        <ApplicationStatusBadge status={app.status} />
      </div>
      {query.isError && <ErrorState error={query.error} retry={() => void query.refetch()} />}
      <div className={styles.detailGrid}>
        <section className="panel">
          <DecisionPanel application={app} />
          <h2>О вашей заявке</h2>
          <ApplicationSummary
            data={app}
            productName={
              products.data?.find((product) => product.code === app.productCode)?.name ??
              'Продукт не выбран'
            }
            companyName={company.data?.name}
          />
        </section>
        <aside className="panel">
          <h2>Путь заявки</h2>
          <StatusTimeline status={app.status} />
          {app.status !== 'DRAFT' && <DemoDecision applicationId={app.id} />}
        </aside>
      </div>
      <div className={styles.detailGrid} style={{ marginTop: 24 }}>
        <section className="panel">
          <h2>Документы заявки</h2>
          {documents.isPending ? (
            <LoadingState />
          ) : documents.isError ? (
            <ErrorState error={documents.error} retry={() => void documents.refetch()} />
          ) : (
            <DocumentList id={id} documents={documents.data} />
          )}
        </section>
        <section className="panel">
          <h2>История заявки</h2>
          {history.isPending ? (
            <LoadingState />
          ) : history.isError ? (
            <ErrorState error={history.error} retry={() => void history.refetch()} />
          ) : (
            <ApplicationHistory history={history.data} />
          )}
        </section>
      </div>
    </>
  );
}
