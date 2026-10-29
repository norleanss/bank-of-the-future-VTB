import { Controller, useFormContext, useWatch } from 'react-hook-form';
import type { ApplicationFormData, CreditProduct, Dictionaries } from '../../shared/types/models';
import { money } from '../../shared/lib/format';
import { termRange } from '../../shared/lib/creditTerms';
import styles from './form.module.css';
import { Input } from '../../shared/ui/Field';
import { Select } from '../../shared/ui/Select';
import { Checkbox } from '../../shared/ui/Choice';

export function CreditFields({
  product,
  dictionaries,
}: {
  product: CreditProduct;
  dictionaries: Dictionaries;
}) {
  const { control } = useFormContext<ApplicationFormData>();
  const subscription = useWatch({ control, name: 'subscription' });
  const unit = useWatch({ control, name: 'termUnit' });
  const selects = [
    { name: 'purpose', label: 'Цель кредита', options: dictionaries.purposes },
    {
      name: 'termUnit',
      label: 'Единица срока',
      options: [
        { code: 'MONTHS', label: 'Месяцы' },
        { code: 'DAYS', label: 'Дни' },
      ],
    },
    {
      name: 'fundingMode',
      label: 'Способ получения денег',
      options: dictionaries.fundingModes.filter((item) => product.fundingModes.includes(item.code)),
    },
    {
      name: 'repaymentType',
      label: 'Способ погашения',
      options: dictionaries.repaymentTypes.filter((item) =>
        product.repaymentTypes.includes(item.code),
      ),
    },
  ] as const;
  return (
    <div className={styles.formFields}>
      <Controller
        name="requestedAmount"
        control={control}
        render={({ field, fieldState }) => (
          <Input
            {...field}
            label="Сумма кредита, ₽"
            inputMode="decimal"
            hint={`От ${money(product.minAmount)} до ${money(product.maxAmount)}`}
            error={fieldState.error?.message}
          />
        )}
      />
      <Controller
        name="term"
        control={control}
        render={({ field, fieldState }) => (
          <Input
            {...field}
            value={Number.isFinite(field.value) ? String(field.value) : ''}
            onChange={(event) => field.onChange(event.target.valueAsNumber)}
            type="number"
            inputMode="numeric"
            label={unit === 'DAYS' ? 'Срок, дней' : 'Срок, месяцев'}
            error={fieldState.error?.message}
            hint={`${product.termKind === 'LOAN' ? 'Кредит' : 'Один транш'}: ${termRange(product.minTerm, subscription ? (product.maxTermWithSubscription ?? product.maxTerm) : product.maxTerm, product.termUnit)}`}
          />
        )}
      />
      {selects.map((question) => (
        <Controller
          key={question.name}
          name={question.name}
          control={control}
          render={({ field, fieldState }) => (
            <Select
              label={question.label}
              name={field.name}
              ref={field.ref}
              options={question.options.map((item) => ({
                key: item.code,
                content: item.label,
              }))}
              selected={field.value}
              onChange={({ selected }) => field.onChange(selected?.key)}
              onBlur={field.onBlur}
              error={fieldState.error?.message}
            />
          )}
        />
      ))}
      {product.rateWithSubscription !== undefined && (
        <div className={styles.fullWidth}>
          <Controller
            name="subscription"
            control={control}
            render={({ field }) => (
              <Checkbox
                label="Оформить с подпиской"
                name={field.name}
                checked={field.value}
                onChange={(checked) => field.onChange(checked)}
              />
            )}
          />
          <details className={styles.creditDetails}>
            <summary>Условия подписки и договора</summary>
            <p>{product.subscriptionDescription}</p>
            {product.agreementTermMonths && (
              <p>
                Договор на {product.agreementTermMonths} мес.
                {product.autoRenewal ? ', с автопродлением' : ''}.
              </p>
            )}
          </details>
        </div>
      )}
    </div>
  );
}
