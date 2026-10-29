import type { StatusHistoryItem } from '../../shared/types/models';
import { statusLabels } from '../../entities/application/status';
import { dateTime } from '../../shared/lib/format';

export function ApplicationHistory({ history }: { history: StatusHistoryItem[] }) {
  return history.length ? (
    <ol className="applicationHistory">
      {history.map((event, index) => (
        <li key={`${event.createdAt}-${index}`}>
          <strong>{statusLabels[event.toStatus]}</strong>
          <p className="muted">
            {dateTime(event.createdAt)} ·{' '}
            {event.actorName ??
              {
                CLIENT: 'Клиент',
                EMPLOYEE: 'Сотрудник банка',
                SYSTEM: 'Система',
              }[event.actorType]}
          </p>
          {event.comment && <p>{event.comment}</p>}
        </li>
      ))}
    </ol>
  ) : (
    <p className="muted">История заявки пока пуста.</p>
  );
}
