import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useForm, Controller, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery } from '@tanstack/react-query';
import type { SelectionData } from '../shared/types/models';
import { selectionSchema } from '../entities/credit/validation';
import { getWorkspace, saveWorkspace } from '../shared/lib/storage';
import { useCompany } from '../app/useCompany';
import { dictionariesQuery } from '../app/queries';
import { StepProgress } from '../shared/ui/StepProgress';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import styles from './pages.module.css';
import { Button } from '../shared/ui/Button';
import { Input } from '../shared/ui/Field';
import { Radio } from '../shared/ui/Choice';

const labels = ['Цель', 'Сумма', 'Срок', 'Получение'];
const fields: FieldPath<SelectionData>[][] = [
  ['purpose'],
  ['requestedAmount'],
  ['term', 'termUnit'],
  ['fundingMode'],
];
const titles = [
  'Для чего вам нужны деньги?',
  'Какая сумма нужна бизнесу?',
  'На какой срок нужны деньги?',
  'Как хотите получать деньги?',
];
export function SelectionPage() {
  const company = useCompany();
  const dictionaries = useQuery(dictionariesQuery);
  const [workspace] = useState(() => getWorkspace(company?.id));
  const [step, setStep] = useState(workspace.selectionStep);
  const [error, setError] = useState<unknown>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const navigate = useNavigate();
  const form = useForm<SelectionData>({
    resolver: zodResolver(selectionSchema),
    defaultValues: {
      companyId: company?.id,
      purpose: 'WORKING_CAPITAL',
      requestedAmount: '1000000.00',
      term: 12,
      termUnit: 'MONTHS',
      fundingMode: 'LUMP_SUM',
      ...workspace.selection,
    },
  });
  useEffect(() => {
    const subscription = form.watch(() => {
      try {
        saveWorkspace({ selection: form.getValues() }, company?.id);
        setError(null);
      } catch (err) {
        setError(err);
      }
    });
    return () => subscription.unsubscribe();
  }, [form, company?.id]);
  useEffect(() => {
    const frame = requestAnimationFrame(() => heading.current?.focus({ preventScroll: true }));
    window.scrollTo({ top: 0 });
    return () => cancelAnimationFrame(frame);
  }, [step, dictionaries.isSuccess]);
  const moveTo = (next: number) => {
    try {
      saveWorkspace({ selection: form.getValues(), selectionStep: next }, company?.id);
      setStep(next);
      setError(null);
    } catch (err) {
      setError(err);
    }
  };
  const next = async () => {
    if (step < 3) {
      if (await form.trigger(fields[step], { shouldFocus: true })) moveTo(step + 1);
    } else
      await form.handleSubmit((data) => {
        try {
          saveWorkspace({ selection: data }, company?.id);
          navigate('/credit-products');
        } catch (err) {
          setError(err);
        }
      })();
  };
  if (dictionaries.isPending) return <LoadingState />;
  if (dictionaries.isError)
    return <ErrorState error={dictionaries.error} retry={() => void dictionaries.refetch()} />;
  return (
    <div className={styles.formPage}>
      <div className={styles.formHeader}>
        <Link className="back" to="/">
          ← На главную
        </Link>
        <span className="muted">Подбор для {company?.name}</span>
      </div>
      <section className={`panel ${styles.formPanel}`}>
        <StepProgress labels={labels} current={step} />
        <h1 className={styles.formTitle} tabIndex={-1} ref={heading}>
          {titles[step]}
        </h1>
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void next();
          }}
        >
          {step === 0 && (
            <Controller
              name="purpose"
              control={form.control}
              render={({ field, fieldState }) => (
                <fieldset className={`${styles.purposes} ${styles.purposeGrid}`}>
                  <legend className={styles.srOnly}>Цель кредита</legend>
                  {dictionaries.data.purposes.map((item) => (
                    <Radio
                      key={item.code}
                      label={item.label}
                      name={field.name}
                      checked={field.value === item.code}
                      onChange={() => field.onChange(item.code)}
                      block
                      className={styles.purpose}
                    />
                  ))}
                  {fieldState.error && (
                    <p role="alert" className={styles.fieldError}>
                      {fieldState.error.message}
                    </p>
                  )}
                </fieldset>
              )}
            />
          )}
          {step === 1 && (
            <Controller
              name="requestedAmount"
              control={form.control}
              render={({ field, fieldState }) => (
                <Input
                  {...field}
                  label="Необходимая сумма, ₽"
                  inputMode="decimal"
                  error={fieldState.error?.message}
                  hint="Укажите сумму кредита или желаемый лимит"
                />
              )}
            />
          )}
          {step === 2 && (
            <div className="fields">
              <Controller
                name="termUnit"
                control={form.control}
                render={({ field }) => (
                  <fieldset className={styles.purposes}>
                    <legend>Единица срока</legend>
                    {(
                      [
                        ['MONTHS', 'Месяцы'],
                        ['DAYS', 'Дни'],
                      ] as const
                    ).map(([value, label]) => (
                      <Radio
                        key={value}
                        label={label}
                        name={field.name}
                        checked={field.value === value}
                        onChange={() => field.onChange(value)}
                      />
                    ))}
                  </fieldset>
                )}
              />
              <Controller
                name="term"
                control={form.control}
                render={({ field, fieldState }) => (
                  <Input
                    {...field}
                    value={Number.isFinite(field.value) ? String(field.value) : ''}
                    onChange={(event) => field.onChange(event.target.valueAsNumber)}
                    type="number"
                    inputMode="numeric"
                    label={form.watch('termUnit') === 'DAYS' ? 'Срок, дней' : 'Срок, месяцев'}
                    error={fieldState.error?.message}
                    hint="Для кредита одной суммой — срок погашения. Для линии и овердрафта — срок одного транша."
                  />
                )}
              />
            </div>
          )}
          {step === 3 && (
            <Controller
              name="fundingMode"
              control={form.control}
              render={({ field }) => (
                <fieldset className={styles.purposes}>
                  <legend className={styles.srOnly}>Способ получения денег</legend>
                  {dictionaries.data.fundingModes.map((item) => (
                    <Radio
                      key={item.code}
                      label={item.label}
                      name={field.name}
                      checked={field.value === item.code}
                      onChange={() => field.onChange(item.code)}
                      block
                      className={styles.purpose}
                    />
                  ))}
                </fieldset>
              )}
            />
          )}
          {error !== null && <ErrorState error={error} />}
          <div className="actions">
            {step > 0 && <Button onClick={() => moveTo(step - 1)}>Назад</Button>}
            <Button view="primary" type="submit">
              {step === 3 ? 'Показать подходящие продукты' : 'Далее'}
            </Button>
          </div>
        </form>
      </section>
    </div>
  );
}
