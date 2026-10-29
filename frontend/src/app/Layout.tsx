import { useEffect, useState } from 'react';
import { NavLink, Outlet, Navigate, useLocation, useNavigate, Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
import { getSelectedCompanyId, selectCompany } from '../shared/lib/storage';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import { sessionQuery } from './queries';
import styles from './Layout.module.css';
import { Button } from '../shared/ui/Button';
import { Select } from '../shared/ui/Select';
import { HomeIcon, WalletIcon, DocumentIcon } from '../shared/ui/icons';

export function Layout() {
  const session = useQuery(sessionQuery);
  const client = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const [selectedId, setSelectedId] = useState(getSelectedCompanyId);
  const companies = session.data?.user.companies ?? [];
  const selected = companies.find((item) => item.id === selectedId) ?? companies[0];
  const isBank = session.data?.user.role === 'UNDERWRITER';
  const notifications = useQuery({
    queryKey: ['notifications', 'unread'],
    queryFn: () => creditApi.getNotifications(true),
    enabled: session.data?.user.role === 'CLIENT',
    refetchInterval: 10_000,
  });
  const accessToken = session.data?.accessToken;
  useEffect(() => {
    if (!accessToken || isBank) return;
    return creditApi.subscribeNotifications?.(() => {
      void client.invalidateQueries({ queryKey: ['notifications'] });
      void client.invalidateQueries({ queryKey: ['applications'] });
      void client.invalidateQueries({ queryKey: ['documents'] });
    });
  }, [accessToken, isBank, client]);
  useEffect(() => {
    if (selected && selected.id !== getSelectedCompanyId()) selectCompany(selected.id);
  }, [selected]);
  useEffect(() => {
    const expired = () => {
      client.clear();
      client.setQueryData(sessionQuery.queryKey, null);
    };
    window.addEventListener('session-expired', expired);
    return () => window.removeEventListener('session-expired', expired);
  }, [client]);
  useEffect(() => {
    document.title = 'Умный кредитный кабинет · Гамма-Банк';
    window.scrollTo({ top: 0 });
    document.getElementById('main-content')?.focus();
  }, [location.pathname]);
  const logout = useMutation({
    mutationFn: () => creditApi.logout(),
    onSuccess: () => {
      client.clear();
      navigate('/login', { replace: true });
    },
  });
  if (session.isPending) return <LoadingState />;
  if (session.isError)
    return <ErrorState error={session.error} retry={() => void session.refetch()} />;
  if (!session.data) return <Navigate to="/login" replace />;
  if (isBank && !location.pathname.startsWith('/backoffice'))
    return <Navigate to="/backoffice" replace />;
  if (!isBank && location.pathname.startsWith('/backoffice')) return <Navigate to="/" replace />;
  return (
    <div className={styles.shell}>
      <a className={styles.skip} href="#main-content">
        К содержимому
      </a>
      <header className={styles.topbar}>
        <div className={styles.topbarInner}>
          <Link
            to={isBank ? '/backoffice' : '/'}
            className={styles.brand}
            aria-label="Гамма-Банк · Умный кредитный кабинет"
          >
            <span className={styles.brandMark} aria-hidden="true">
              Г
            </span>
            <span>
              <strong>Гамма-Банк</strong>
              <small>Бизнес</small>
            </span>
          </Link>
          <nav aria-label="Основная навигация" className={styles.nav}>
            {isBank ? (
              <NavLink to="/backoffice">
                <DocumentIcon aria-hidden="true" />
                <span>Очередь заявок</span>
              </NavLink>
            ) : (
              <>
                <NavLink to="/" end>
                  <HomeIcon aria-hidden="true" />
                  <span>Главная</span>
                </NavLink>
                <NavLink to="/credit-products">
                  <WalletIcon aria-hidden="true" />
                  <span>Кредиты</span>
                </NavLink>
                <NavLink to="/profile">
                  <DocumentIcon aria-hidden="true" />
                  <span>Личный кабинет</span>
                </NavLink>
              </>
            )}
          </nav>
          <div className={styles.companyPicker}>
            {isBank ? (
              <strong>Кабинет сотрудника банка</strong>
            ) : selected ? (
              <Select
                label="Демо-режим: выберите компанию"
                options={companies.map((item) => ({
                  key: item.id,
                  content: (
                    <span>
                      {item.name}
                      <small className={styles.companyMeta}>ИНН {item.inn}</small>
                    </span>
                  ),
                }))}
                selected={selected.id}
                valueRenderer={({ selected: option }) =>
                  companies.find((item) => item.id === option?.key)?.name ?? 'Выберите компанию'
                }
                onChange={({ selected: next }) => {
                  if (!next || next.key === selected.id) return;
                  selectCompany(next.key);
                  setSelectedId(next.key);
                  navigate('/', { replace: true });
                }}
              />
            ) : (
              <span>Нет доступных компаний</span>
            )}
          </div>
          <div className={styles.account}>
            {!isBank && (
              <Link className={styles.notifications} to="/notifications">
                Уведомления
                {notifications.data?.totalElements ? ` (${notifications.data.totalElements})` : ''}
              </Link>
            )}
            <span className={styles.user}>{session.data.user.fullName}</span>
            <Button
              size={40}
              view="text"
              className={styles.logout}
              loading={logout.isPending}
              onClick={() => logout.mutate()}
            >
              Выйти
            </Button>
          </div>
        </div>
      </header>
      <div className={styles.workspace}>
        <div className={styles.sheet}>
          <main id="main-content" tabIndex={-1} className="container">
            {logout.isError && <ErrorState error={logout.error} />}
            {!isBank && !selected ? (
              <section className="panel">
                <h1>Компании пока не доступны</h1>
                <p>Для оформления заявки нужна компания в профиле клиента.</p>
              </section>
            ) : (
              <Outlet key={isBank ? 'bank' : selected?.id} />
            )}
          </main>
          <footer className={styles.footer}>
            <span>Гамма-Банк · Умный кредитный кабинет</span>
            <span>Демонстрационный кабинет: все условия и решения — тестовые</span>
          </footer>
        </div>
      </div>
    </div>
  );
}
