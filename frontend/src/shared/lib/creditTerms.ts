import type { RateUnit, TermUnit } from '../types/models';
export function rateLabel(value: number, unit: RateUnit): string {
  return `${value.toLocaleString('ru-RU')}% ${unit === 'PERCENT_PER_DAY' ? 'в день' : 'годовых'}`;
}
export function termLabel(value: number, unit: TermUnit): string {
  return `${value} ${unit === 'DAYS' ? 'дн.' : 'мес.'}`;
}
export function termRange(min: number, max: number, unit: TermUnit): string {
  return min === max ? termLabel(max, unit) : `${min}–${termLabel(max, unit)}`;
}
