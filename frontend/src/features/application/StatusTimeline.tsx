import type { ApplicationStatus } from '../../shared/types/models';
import styles from './status.module.css';
import { CheckIcon } from '../../shared/ui/icons';
const stage: Record<ApplicationStatus, number> = {
  DRAFT: -1,
  SUBMITTED: 0,
  ENRICHMENT: 1,
  SCORING: 2,
  NEED_INFO: 2,
  MANUAL_REVIEW: 2,
  APPROVED: 3,
  COUNTER_OFFER: 3,
  REJECTED: 3,
  OFFER_DECLINED: 3,
  OFFER_EXPIRED: 3,
  PENDING_CONFIRMATION: 4,
  OFFER_ACCEPTED: 5,
  SIGNED: 6,
  DISBURSED: 6,
  CANCELLED: -1,
};
const steps = [
  'Заявка отправлена',
  'Проверка данных',
  'Анализ заявки',
  'Решение банка',
  'Подтверждение условий',
  'Подписание',
  'Выдача',
];
export function StatusTimeline({ status }: { status: ApplicationStatus }) {
  if (status === 'CANCELLED')
    return <p className="muted">Заявка отменена. Обработка остановлена.</p>;
  const stopped = ['REJECTED', 'OFFER_DECLINED', 'OFFER_EXPIRED'].includes(status);
  return (
    <ol className={styles.timeline}>
      {steps.map((label, index) => {
        const done = index < stage[status] || status === 'DISBURSED';
        const current = index === stage[status];
        const hint = done
          ? 'Завершено'
          : stopped
            ? current
              ? 'Заявка закрыта'
              : 'Не выполняется'
            : current
              ? 'Текущий этап'
              : 'Впереди';
        return (
          <li
            key={label}
            className={done ? styles.done : current ? styles.current : undefined}
            aria-current={current ? 'step' : undefined}
          >
            <span className={styles.dot} aria-hidden="true">
              {done ? <CheckIcon /> : index + 1}
            </span>
            <div>
              <strong>{label}</strong>
              <span>{hint}</span>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
