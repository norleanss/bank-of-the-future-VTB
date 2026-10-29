import type { ApplicationStatus } from '../../shared/types/models';
export const statusLabels: Record<ApplicationStatus, string> = {
  DRAFT: 'Черновик',
  SUBMITTED: 'Заявка отправлена',
  ENRICHMENT: 'Проверяем данные компании',
  SCORING: 'Анализируем заявку',
  NEED_INFO: 'Нужны дополнительные данные',
  MANUAL_REVIEW: 'На рассмотрении у специалиста',
  APPROVED: 'Кредит одобрен',
  COUNTER_OFFER: 'Мы готовы предложить другие условия',
  PENDING_CONFIRMATION: 'Подтверждаем условия',
  OFFER_ACCEPTED: 'Готово к подписанию',
  SIGNED: 'Договор подписан',
  DISBURSED: 'Деньги выданы',
  REJECTED: 'Сейчас мы не можем одобрить заявку',
  OFFER_DECLINED: 'Вы отказались от предложения',
  OFFER_EXPIRED: 'Срок предложения истёк',
  CANCELLED: 'Заявка отменена',
};
export const completedStatuses: ApplicationStatus[] = [
  'DISBURSED',
  'REJECTED',
  'OFFER_DECLINED',
  'OFFER_EXPIRED',
  'CANCELLED',
];
export const isCompleted = (status: ApplicationStatus) => completedStatuses.includes(status);
