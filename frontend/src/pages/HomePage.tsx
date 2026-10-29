import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { applicationsQuery, sessionQuery } from '../app/queries';
import { useCompany } from '../app/useCompany';
import { ApplicationList } from '../entities/application/ApplicationList';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import styles from './pages.module.css';
import { Button } from '../shared/ui/Button';
export function HomePage() {
  const company = useCompany();
  const { data: session } = useQuery(sessionQuery);
  const applications = useQuery(applicationsQuery(company?.id ?? ''));
  return (
    <>
      <div className={styles.homeHeading}>
        <div>
          <h1>Здорово, {session?.user.fullName.split(' ')[0]}!</h1>
          <p className="muted">{company?.name}</p>
        </div>
      </div>
      <div className={styles.homeGrid}>
        <section className={styles.hero}>
          <div className="eyebrow">Финансирование бизнеса</div>
          <h2>Давай подберём кредит для бизнеса</h2>
          <p>Ответьте на четыре вопроса, сравните продукты и оформите заявку.</p>
          <dl className={styles.heroTerms}>
            <div>
              <dt>Подбор</dt>
              <dd>4 вопроса</dd>
            </div>
            <div>
              <dt>Все этапы</dt>
              <dd>В кабинете</dd>
            </div>
          </dl>
          <Button view="primary" href="/credit-selection">
            Подобрать кредит
          </Button>
        </section>
        <aside className={styles.journey}>
          <h2>От заявки до выдачи</h2>
          <p className="muted">Всё можно сделать здесь</p>
          <ol>
            {[
              ['Подберите продукт', 'Цель, сумма, срок и способ выдачи'],
              ['Получите решение', 'Статус и запросы банка в кабинете'],
              ['Подпишите договор', 'Примите условия и подтвердите кодом'],
            ].map(([title, text], index) => (
              <li key={title}>
                <span aria-hidden="true">{index + 1}</span>
                <div>
                  <strong>{title}</strong>
                  <p>{text}</p>
                </div>
              </li>
            ))}
          </ol>
        </aside>
      </div>
      <section className={styles.applicationsSection}>
        <div className={styles.sectionHeading}>
          <h2>Заявки компании</h2>
          <Link to="/profile">Личный кабинет →</Link>
        </div>
        {applications.isPending ? (
          <LoadingState />
        ) : applications.isError ? (
          <ErrorState error={applications.error} retry={() => void applications.refetch()} />
        ) : applications.data.content.length ? (
          <ApplicationList applications={applications.data.content.slice(0, 2)} />
        ) : (
          <div className={styles.empty}>
            <div>
              <h3>Здесь появится ваша первая заявка</h3>
              <p>Начните с подбора — параметры сохранятся для анкеты.</p>
            </div>
          </div>
        )}
      </section>
    </>
  );
}
