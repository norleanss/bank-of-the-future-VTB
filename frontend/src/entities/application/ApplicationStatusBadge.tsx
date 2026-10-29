import { useQuery } from '@tanstack/react-query';
import type { ApplicationStatus } from '../../shared/types/models';
import { dictionariesQuery } from '../../app/queries';
import { statusLabels } from './status';
import styles from '../../shared/ui/controls.module.css';
export function ApplicationStatusBadge({ status }: { status: ApplicationStatus }) {
  const dictionaries = useQuery(dictionariesQuery);
  const tone = ['APPROVED', 'OFFER_ACCEPTED', 'DISBURSED'].includes(status)
    ? 'green'
    : ['NEED_INFO', 'COUNTER_OFFER', 'MANUAL_REVIEW', 'PENDING_CONFIRMATION'].includes(status)
      ? 'orange'
      : status === 'REJECTED'
        ? 'red'
        : status === 'SCORING'
          ? 'blue'
          : 'grey';
  return (
    <span className={styles.status} data-tone={tone}>
      {dictionaries.data?.applicationStatuses.find((item) => item.code === status)?.label ??
        statusLabels[status]}
    </span>
  );
}
