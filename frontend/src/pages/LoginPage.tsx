import { useEffect } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import type { Role } from '../shared/types/models';
import { sessionQuery } from '../app/queries';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import styles from './pages.module.css';
import { Button } from '../shared/ui/Button';
import { CheckIcon } from '../shared/ui/icons';

export function LoginPage() {
  useEffect(() => {
    document.title = 'Вход · Умный кредитный кабинет';
  }, []);
  const session = useQuery(sessionQuery);
  const client = useQueryClient();
  const navigate = useNavigate();
  const login = useMutation({
    mutationFn: (role: Role) => creditApi.login(role),
    onSuccess: (data) => {
      client.clear();
      client.setQueryData(sessionQuery.queryKey, data);
      navigate(data.user.role === 'CLIENT' ? '/' : '/backoffice', {
        replace: true,
      });
    },
  });
  if (session.isPending) return <LoadingState />;
  if (session.isError)
    return <ErrorState error={session.error} retry={() => void session.refetch()} />;
  if (session.data)
    return <Navigate to={session.data.user.role === 'CLIENT' ? '/' : '/backoffice'} replace />;
  return (
    <div className={styles.loginPage}>
      <main className={styles.login}>
        <section className={styles.loginIntro}>
          <div className={styles.loginBrand}>
            <span aria-hidden="true">Г</span>Гамма-Банк · Бизнес
          </div>
          <div className="eyebrow">Умный кредитный кабинет</div>
          <h1>Возможности для бизнеса</h1>
          <p>Подберите финансирование и пройдите путь от заявки до выдачи в одном кабинете.</p>
          <ul className={styles.loginSteps}>
            {['Выберите компанию и кредит', 'Получите решение', 'Подпишите договор в кабинете'].map(
              (text) => (
                <li key={text}>
                  <CheckIcon aria-hidden="true" />
                  {text}
                </li>
              ),
            )}
          </ul>
        </section>
        <section className={`panel ${styles.loginForm}`}>
          <span className="badge">Демо-кабинет</span>
          <h2>Посмотрите, как это работает</h2>
          <p className="muted">
            У клиента — шесть компаний с разными сценариями. Сотрудник банка рассматривает заявки и
            подтверждает условия.
          </p>
          <div className="fields">
            <Button
              block
              view="primary"
              disabled={login.isPending}
              loading={login.isPending && login.variables === 'CLIENT'}
              onClick={() => login.mutate('CLIENT')}
            >
              Войти как клиент
            </Button>
            <Button
              block
              disabled={login.isPending}
              loading={login.isPending && login.variables === 'UNDERWRITER'}
              onClick={() => login.mutate('UNDERWRITER')}
            >
              Войти как сотрудник банка
            </Button>
          </div>
          {login.isError && <ErrorState error={login.error} />}
          <p className={styles.loginHint}>
            Телефон и SMS не нужны. Компании, решения и подписание используются для демонстрации.
          </p>
        </section>
      </main>
    </div>
  );
}
