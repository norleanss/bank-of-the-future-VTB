export function normalizeMoney(value: string): string {
  const cleaned = value.replace(/\s/g, '').replace(',', '.');
  if (!/^\d{1,15}(\.\d{1,2})?$/.test(cleaned)) return cleaned;
  const [whole = '0', fraction = ''] = cleaned.split('.');
  return `${BigInt(whole)}.${fraction.padEnd(2, '0')}`;
}
export function toKopecks(value: string): bigint {
  const normalized = normalizeMoney(value);
  if (!/^\d{1,15}\.\d{2}$/.test(normalized)) throw new Error('Некорректная денежная сумма');
  return BigInt(normalized.replace('.', ''));
}
export function fromKopecks(value: bigint): string {
  return `${value / 100n}.${String(value % 100n).padStart(2, '0')}`;
}
