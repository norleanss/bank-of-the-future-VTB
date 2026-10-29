import { useMutation, useQueryClient } from '@tanstack/react-query';
import { creditApi } from '../../shared/api';
import type { ApplicationAction, CreditApplication } from '../../shared/types/models';
import { statusLabels } from '../../entities/application/status';
import { money, dateTime } from '../../shared/lib/format';
import { rateLabel, termLabel } from '../../shared/lib/creditTerms';
import { ErrorState } from '../../shared/ui/Feedback';
import { SigningForm } from './SigningForm';
import { AdditionalInfoForm } from './AdditionalInfoForm';
import styles from './status.module.css';
import { Button } from '../../shared/ui/Button';

export function DecisionPanel({ application: app }: { application: CreditApplication }) {
  const client = useQueryClient();
  const update = (updated: CreditApplication) => {
    client.setQueryData(['applications', updated.id], updated);
    void client.invalidateQueries({ queryKey: ['applications', 'list'] });
    void client.invalidateQueries({ queryKey: ['applications', updated.id, 'history'] });
    void client.invalidateQueries({ queryKey: ['documents', updated.id] });
    void client.invalidateQueries({ queryKey: ['notifications'] });
  };
  const action = useMutation({
    mutationFn: (name: ApplicationAction) => {
      if (name === 'ACCEPT_OFFER') return creditApi.acceptOffer(app.id);
      if (name === 'DECLINE_OFFER') return creditApi.declineOffer(app.id);
      if (name === 'SUBMIT') return creditApi.submitApplication(app.id);
      return creditApi.cancelApplication(app.id);
    },
    onSuccess: update,
    onError: () => {
      void client.invalidateQueries({ queryKey: ['applications', app.id] });
    },
  });
  const offer = app.offer;
  const infoMessage = app.availableActions.includes('RESPOND_INFO')
    ? app.openInfoRequest?.message.trim()
    : undefined;
  return (
    <section
      className={`${styles.decision} ${app.status === 'APPROVED' || app.status === 'DISBURSED' ? styles.approved : ''}`}
      data-status={app.status}
      aria-live="polite"
    >
      <h2>{statusLabels[app.status]}</h2>
      {app.nextStepHint && <p>{app.nextStepHint}</p>}
      {app.reasons.map((reason) => (
        <div key={reason.code}>
          {reason.message.trim() !== infoMessage && <p>{reason.message}</p>}
          {reason.advice && (
            <p className="notice">
              <strong>Что можно сделать</strong>
              <br />
              {reason.advice}
            </p>
          )}
        </div>
      ))}
      {offer && (
        <dl className="summary">
          <div>
            <dt>Предложение банка</dt>
            <dd>{offer.productName}</dd>
          </div>
          <div>
            <dt>{offer.termKind === 'TRANCHE' ? 'Одобренный лимит' : 'Сумма кредита'}</dt>
            <dd>{money(offer.amount)}</dd>
          </div>
          <div>
            <dt>{offer.termKind === 'TRANCHE' ? 'Срок одного транша, до' : 'Срок кредита'}</dt>
            <dd>{termLabel(offer.term, offer.termUnit)}</dd>
          </div>
          {offer.agreementTermMonths && (
            <div>
              <dt>Срок договора</dt>
              <dd>{offer.agreementTermMonths} мес.</dd>
            </div>
          )}
          <div>
            <dt>Ставка</dt>
            <dd>{rateLabel(offer.rate, offer.rateUnit)}</dd>
          </div>
          {offer.monthlyPayment && (
            <div>
              <dt>
                {offer.termKind === 'TRANCHE'
                  ? 'Проценты в месяц при полном использовании'
                  : 'Ежемесячный платёж'}
              </dt>
              <dd>{money(offer.monthlyPayment)}</dd>
            </div>
          )}
          <div>
            <dt>Подписка</dt>
            <dd>{offer.subscription ? 'С подпиской' : 'Без подписки'}</dd>
          </div>
          {offer.subscriptionFee && (
            <div>
              <dt>Комиссия за подписку при выдаче</dt>
              <dd>{money(offer.subscriptionFee)} · отдельно от ежемесячного платежа</dd>
            </div>
          )}
          {['APPROVED', 'COUNTER_OFFER'].includes(app.status) && (
            <div>
              <dt>Предложение действует до</dt>
              <dd>{dateTime(offer.expiresAt)} мск</dd>
            </div>
          )}
        </dl>
      )}
      {offer && <p className="muted">Условия демонстрационные</p>}
      {offer?.preliminary && app.status === 'APPROVED' && (
        <p>
          Это предварительное одобрение. После вашего согласия условия подтвердит сотрудник банка.
        </p>
      )}
      {app.availableActions.includes('RESPOND_INFO') && (
        <AdditionalInfoForm key={app.openInfoRequest?.id} application={app} onSuccess={update} />
      )}
      {app.availableActions.includes('SIGN') && <SigningForm id={app.id} onSuccess={update} />}
      <div className="actions">
        {app.availableActions.includes('EDIT') && (
          <Button href={`/applications/new?draft=${app.id}`}>
            Продолжить заполнение
          </Button>
        )}
        {(['ACCEPT_OFFER', 'DECLINE_OFFER', 'SUBMIT', 'CANCEL'] as const)
          .filter((name) => app.availableActions.includes(name))
          .map((name) => (
            <Button
              key={name}
              view={name === 'ACCEPT_OFFER' || name === 'SUBMIT' ? 'primary' : 'secondary'}
              loading={action.isPending && action.variables === name}
              disabled={action.isPending}
              onClick={() => action.mutate(name)}
            >
              {
                {
                  ACCEPT_OFFER: 'Принять предложение',
                  DECLINE_OFFER: 'Отказаться от предложения',
                  SUBMIT: 'Отправить заявку',
                  CANCEL: 'Отменить заявку',
                }[name]
              }
            </Button>
          ))}
        {app.status === 'OFFER_EXPIRED' && (
          <Button href="/credit-selection">
            Подать новую заявку
          </Button>
        )}
      </div>
      {action.isError && <ErrorState error={action.error} />}
    </section>
  );
}
