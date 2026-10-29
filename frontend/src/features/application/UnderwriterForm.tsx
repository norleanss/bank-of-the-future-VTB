import { useMutation, useQuery } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { creditApi } from '../../shared/api';
import { ApiError } from '../../shared/api/errors';
import { dictionariesQuery, productsQuery } from '../../app/queries';
import { normalizeMoney } from '../../shared/lib/money';
import type { CreditApplication, DocumentType } from '../../shared/types/models';
import { infoFieldLabels, documentLabels } from '../../shared/lib/applicationFields';
import { ErrorState, LoadingState } from '../../shared/ui/Feedback';
import { Button } from '../../shared/ui/Button';
import { Input, Textarea } from '../../shared/ui/Field';
import { Select } from '../../shared/ui/Select';
import { Checkbox } from '../../shared/ui/Choice';

const schema = z
  .object({
    decision: z.enum(['APPROVE', 'COUNTER_OFFER', 'REJECT']),
    productCode: z.enum(['LUMP_SUM_LOAN', 'CREDIT_LINE', 'OVERDRAFT', 'GAMMA_START']),
    amount: z.string(),
    term: z.number().or(z.nan()),
    termUnit: z.enum(['MONTHS', 'DAYS']),
    rate: z.number().or(z.nan()),
    comment: z.string().max(2000, 'Не больше 2000 символов'),
    reasonCodes: z.array(z.string()),
  })
  .superRefine((data, ctx) => {
    if (data.decision === 'COUNTER_OFFER') {
      if (
        !/^\d{1,15}\.\d{2}$/.test(normalizeMoney(data.amount)) ||
        Number(normalizeMoney(data.amount)) <= 0
      )
        ctx.addIssue({
          code: 'custom',
          path: ['amount'],
          message: 'Введите корректную сумму',
        });
      if (!Number.isInteger(data.term) || data.term < 1)
        ctx.addIssue({
          code: 'custom',
          path: ['term'],
          message: 'Введите целый положительный срок',
        });
      if (
        !Number.isFinite(data.rate) ||
        data.rate < 0 ||
        Math.abs(data.rate * 100 - Math.round(data.rate * 100)) > 1e-8
      )
        ctx.addIssue({
          code: 'custom',
          path: ['rate'],
          message: 'Введите неотрицательную ставку, до двух знаков после запятой',
        });
    }
    if (data.decision !== 'APPROVE' && !data.comment.trim())
      ctx.addIssue({
        code: 'custom',
        path: ['comment'],
        message: 'Добавьте комментарий к решению',
      });
  });
export function UnderwriterForm({
  application: app,
  onSuccess,
}: {
  application: CreditApplication;
  onSuccess: () => void;
}) {
  const products = useQuery(productsQuery);
  const dictionaries = useQuery(dictionariesQuery);
  const product = products.data?.find(
    (item) => item.code === (app.offer?.productCode ?? app.productCode),
  );
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      decision: 'APPROVE',
      productCode: app.offer?.productCode ?? app.productCode,
      amount: app.offer?.amount ?? app.requestedAmount ?? '',
      term: app.offer?.term ?? app.term,
      termUnit: app.offer?.termUnit ?? app.termUnit,
      rate:
        app.offer?.rate ??
        (app.subscription ? product?.rateWithSubscription : undefined) ??
        product?.baseRate ??
        0,
      comment: '',
      reasonCodes: [],
    },
  });
  const selectedProduct = products.data?.find((item) => item.code === form.watch('productCode'));
  const mutation = useMutation({
    mutationFn: (data: z.infer<typeof schema>) =>
      creditApi.makeDecision(
        app.id,
        data.decision !== 'COUNTER_OFFER'
          ? { decision: data.decision, comment: data.comment, reasonCodes: data.reasonCodes }
          : { ...data, amount: normalizeMoney(data.amount) },
      ),
    onSuccess,
    onError: (error) => {
      if (error instanceof ApiError)
        for (const issue of error.fields)
          if (issue.field in form.getValues() || issue.field.startsWith('reasonCodes['))
            form.setError(
              (issue.field.startsWith('reasonCodes[')
                ? 'reasonCodes'
                : issue.field) as keyof z.infer<typeof schema>,
              {
                message: issue.message,
              },
            );
    },
  });
  if (products.isPending || dictionaries.isPending) return <LoadingState />;
  if (products.isError || dictionaries.isError)
    return (
      <ErrorState
        error={products.error ?? dictionaries.error}
        retry={() => {
          void products.refetch();
          void dictionaries.refetch();
        }}
      />
    );
  const selectFields = [
    {
      name: 'decision',
      label: 'Решение сотрудника',
      options: [
        { key: 'APPROVE', content: 'Одобрить' },
        { key: 'COUNTER_OFFER', content: 'Предложить другие условия' },
        { key: 'REJECT', content: 'Отказать' },
      ],
    },
    {
      name: 'productCode',
      label: 'Продукт в предложении',
      options: products.data.map((item) => ({
        key: item.code,
        content: item.name,
      })),
    },
    {
      name: 'termUnit',
      label: 'Единица срока предложения',
      options: [
        { key: 'MONTHS', content: 'Месяцы' },
        { key: 'DAYS', content: 'Дни' },
      ],
    },
  ] satisfies {
    name: 'decision' | 'productCode' | 'termUnit';
    label: string;
    options: { key: string; content: string }[];
  }[];
  return (
    <form
      className="fields"
      noValidate
      onSubmit={form.handleSubmit((data) => mutation.mutate(data))}
    >
      {selectFields
        .filter((item) => item.name === 'decision' || form.watch('decision') === 'COUNTER_OFFER')
        .map((question) => (
          <Controller
            key={question.name}
            name={question.name}
            control={form.control}
            render={({ field, fieldState }) => (
              <Select
                label={question.label}
                ref={field.ref}
                options={question.options}
                selected={field.value}
                onChange={({ selected }) => {
                  field.onChange(selected?.key);
                  if (question.name === 'productCode') {
                    const next = products.data.find((item) => item.code === selected?.key);
                    if (next) {
                      form.setValue(
                        'rate',
                        (app.subscription ? next.rateWithSubscription : undefined) ?? next.baseRate,
                      );
                      form.setValue('termUnit', next.termUnit);
                      form.setValue(
                        'term',
                        Math.max(
                          next.minTerm,
                          Math.min(
                            form.getValues('term'),
                            app.subscription
                              ? (next.maxTermWithSubscription ?? next.maxTerm)
                              : next.maxTerm,
                          ),
                        ),
                      );
                    }
                  }
                }}
                error={fieldState.error?.message}
              />
            )}
          />
        ))}
      {form.watch('decision') === 'APPROVE' && (
        <p className="notice">
          {app.status === 'PENDING_CONFIRMATION'
            ? 'Подтвердим условия, которые клиент уже принял. Для изменения условий выберите «Предложить другие условия».'
            : 'Одобрим сумму и срок из заявки по ставке каталога с учётом подписки. Для изменения условий выберите «Предложить другие условия».'}
        </p>
      )}
      {form.watch('decision') === 'COUNTER_OFFER' && (
        <>
          <Controller
            name="amount"
            control={form.control}
            render={({ field, fieldState }) => (
              <Input
                {...field}
                label="Сумма предложения, ₽"
                inputMode="decimal"
                error={fieldState.error?.message}
              />
            )}
          />
          {(['term', 'rate'] as const).map((name) => (
            <Controller
              key={name}
              name={name}
              control={form.control}
              render={({ field, fieldState }) => (
                <Input
                  {...field}
                  value={Number.isFinite(field.value) ? String(field.value) : ''}
                  onChange={(event) => field.onChange(event.target.valueAsNumber)}
                  type="number"
                  step={name === 'rate' ? '0.01' : '1'}
                  label={
                    name === 'term'
                      ? 'Срок предложения'
                      : `Ставка, % ${selectedProduct?.rateUnit === 'PERCENT_PER_DAY' ? 'в день' : 'годовых'}`
                  }
                  error={fieldState.error?.message}
                />
              )}
            />
          ))}
        </>
      )}
      <Controller
        name="comment"
        control={form.control}
        render={({ field, fieldState }) => (
          <Textarea
            {...field}
            label="Комментарий к решению"
            id="underwriter-comment"
            aria-label="Комментарий к решению"
            error={fieldState.error?.message}
          />
        )}
      />
      <details>
        <summary>Причины решения для учёта банка</summary>
        <Controller
          name="reasonCodes"
          control={form.control}
          render={({ field, fieldState }) => (
            <fieldset className="fields">
              <legend>Коды причин</legend>
              {dictionaries.data.reasonCodes.map((reason) => (
                <Checkbox
                  key={reason.code}
                  label={reason.label}
                  checked={field.value.includes(reason.code)}
                  onChange={(checked) =>
                    field.onChange(
                      checked
                        ? [...field.value, reason.code]
                        : field.value.filter((code) => code !== reason.code),
                    )
                  }
                />
              ))}
              {fieldState.error && <p role="alert">{fieldState.error.message}</p>}
            </fieldset>
          )}
        />
      </details>
      {mutation.isError && <ErrorState error={mutation.error} />}
      <Button view="primary" type="submit" loading={mutation.isPending}>
        Сохранить решение
      </Button>
    </form>
  );
}
export function UnderwriterInfoRequest({ id, onSuccess }: { id: string; onSuccess: () => void }) {
  const form = useForm({
    defaultValues: {
      message: '',
      requestedFields: ['financials.annualRevenue', 'financials.annualExpenses'],
      requestedDocuments: ['FINANCIAL_STATEMENT'] as DocumentType[],
    },
  });
  const mutation = useMutation({
    mutationFn: (values: {
      message: string;
      requestedFields: string[];
      requestedDocuments: DocumentType[];
    }) => creditApi.requestInfo(id, { ...values, message: values.message.trim() }),
    onSuccess,
  });
  return (
    <form className="fields" onSubmit={form.handleSubmit((data) => mutation.mutate(data))}>
      <Controller
        name="message"
        control={form.control}
        rules={{
          required: 'Укажите, какие сведения нужны',
          validate: (value) => !!value.trim() || 'Укажите, какие сведения нужны',
          maxLength: { value: 2000, message: 'Не больше 2000 символов' },
        }}
        render={({ field, fieldState }) => (
          <Textarea
            {...field}
            label="Запрос клиенту"
            id="underwriter-info-message"
            aria-label="Запрос клиенту"
            error={fieldState.error?.message}
          />
        )}
      />
      <Controller
        name="requestedFields"
        control={form.control}
        render={({ field }) => (
          <fieldset className="fields">
            <legend>Сведения для уточнения</legend>
            {Object.entries(infoFieldLabels).map(([key, label]) => (
              <Checkbox
                key={key}
                label={label}
                checked={field.value.includes(key)}
                onChange={(checked) =>
                  field.onChange(
                    checked ? [...field.value, key] : field.value.filter((value) => value !== key),
                  )
                }
              />
            ))}
          </fieldset>
        )}
      />
      <Controller
        name="requestedDocuments"
        control={form.control}
        render={({ field }) => (
          <fieldset className="fields">
            <legend>Запросить документы</legend>
            {(Object.entries(documentLabels) as [DocumentType, string][]).map(([key, label]) => (
              <Checkbox
                key={key}
                label={label}
                checked={field.value.includes(key)}
                onChange={(checked) =>
                  field.onChange(
                    checked ? [...field.value, key] : field.value.filter((value) => value !== key),
                  )
                }
              />
            ))}
          </fieldset>
        )}
      />
      <Button type="submit" loading={mutation.isPending}>
        Запросить сведения
      </Button>
      {mutation.isError && <ErrorState error={mutation.error} />}
    </form>
  );
}
