import { normalizeMoney } from './money';
export function money(value: string): string {
  const [whole = '', fraction = '00'] = normalizeMoney(value).split('.');
  if (!/^\d+$/.test(whole)) return '—';
  return `${BigInt(whole).toLocaleString('ru-RU')}${fraction === '00' ? '' : `,${fraction}`} ₽`;
}
export const date = (value: string) =>
  new Intl.DateTimeFormat('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(value));
export const dateTime = (value: string) =>
  new Intl.DateTimeFormat('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/Moscow',
  }).format(new Date(value));
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : 'Не удалось выполнить запрос. Попробуйте ещё раз.';
