import { Controller, useFormContext } from 'react-hook-form';
import type { ApplicationFormData } from '../../shared/types/models';
import styles from './form.module.css';
import { Input } from '../../shared/ui/Field';
export function FinanceFields() {
  const { control, watch } = useFormContext<ApplicationFormData>();
  return (
    <div className={styles.formFields}>
      {(
        [
          ['annualRevenue', 'Выручка за 12 месяцев, ₽'],
          ['annualExpenses', 'Расходы за 12 месяцев, ₽'],
          ['bankMonthlyTurnover', 'Оборот по счёту в Гамма-Банке в месяц, ₽'],
          ['monthlyDebtPayment', 'Платежи по текущим кредитам в месяц, ₽'],
        ] as const
      ).map(([name, label]) => (
        <Controller
          key={name}
          name={name}
          control={control}
          render={({ field, fieldState }) => (
            <Input
              {...field}
              label={label}
              inputMode="decimal"
              error={fieldState.error?.message}
            />
          )}
        />
      ))}
      <p className={`muted ${styles.fullWidth}`}>
        {watch('productCode') === 'GAMMA_START'
          ? 'Для Гамма-Старта финансовые данные необязательны.'
          : watch('productCode') === 'OVERDRAFT'
            ? 'Если оборотов или кредитных платежей нет, укажите 0.'
            : 'Оборот по счёту необязателен. Если кредитных платежей нет, укажите 0.'}
      </p>
    </div>
  );
}
