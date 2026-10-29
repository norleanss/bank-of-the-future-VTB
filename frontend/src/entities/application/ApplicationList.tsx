import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ApplicationSummary } from '../../shared/types/models';
import { productsQuery } from '../../app/queries';
import { ApplicationStatusBadge } from './ApplicationStatusBadge';
import { money, date } from '../../shared/lib/format';
import styles from './application.module.css';
import { ArrowRightIcon } from '../../shared/ui/icons';

export function ApplicationList({ applications }: { applications: ApplicationSummary[] }) {
  const products = useQuery(productsQuery);
  return (
    <div className={styles.list}>
      {applications.map((app) => (
        <Link
          className={styles.row}
          key={app.id}
          to={
            app.status === 'DRAFT' ? `/applications/new?draft=${app.id}` : `/applications/${app.id}`
          }
        >
          <div>
            <span className={styles.number}>
              {app.number} · {date(app.createdAt)}
            </span>
            <h3>
              {products.data?.find((product) => product.code === app.productCode)?.name ??
                'Заявка на кредит'}
            </h3>
            <p className="muted">{app.companyName}</p>
            <ApplicationStatusBadge status={app.status} />
          </div>
          <div className={styles.amount}>
            {app.requestedAmount ? money(app.requestedAmount) : 'Сумма не указана'}
          </div>
          <ArrowRightIcon className={styles.rowArrow} aria-hidden="true" />
        </Link>
      ))}
    </div>
  );
}
