import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormProvider, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { creditApi } from '../shared/api';
import { ApiError } from '../shared/api/errors';
import {
  getWorkspace,
  saveWorkspace,
  clearApplicationDraft,
  emptyWorkspace,
} from '../shared/lib/storage';
import { productsQuery, dictionariesQuery, companyQuery } from '../app/queries';
import { useCompany } from '../app/useCompany';
import type {
  ApplicationFormData,
  ApplicationPatchRequest,
  Company,
  CreditApplication,
  CreditProduct,
  Dictionaries,
} from '../shared/types/models';
import { schemaForProduct, toApplicationRequest } from '../entities/credit/validation';
import { ApplicationSummary } from '../entities/application/ApplicationSummary';
import { CompanyFields } from '../features/application/CompanyFields';
import { CreditFields } from '../features/application/CreditFields';
import { FinanceFields } from '../features/application/FinanceFields';
import { ErrorState, LoadingState } from '../shared/ui/Feedback';
import { StepProgress } from '../shared/ui/StepProgress';
import styles from './pages.module.css';
import { Button } from '../shared/ui/Button';

const labels = ['Компания', 'Параметры кредита', 'Финансовые данные', 'Проверка'];
const stepFields: FieldPath<ApplicationFormData>[][] = [
  ['companyId'],
  [
    'productCode',
    'requestedAmount',
    'term',
    'termUnit',
    'purpose',
    'fundingMode',
    'repaymentType',
    'subscription',
  ],
  ['annualRevenue', 'annualExpenses', 'bankMonthlyTurnover', 'monthlyDebtPayment'],
];
export function NewApplicationPage() {
  const selected = useCompany();
  const [params] = useSearchParams();
  const [initialWorkspace] = useState(() => getWorkspace(selected?.id));
  const draftId = params.get('draft') ?? initialWorkspace.applicationId;
  const draft = useQuery({
    queryKey: ['applications', draftId],
    queryFn: () => creditApi.getApplication(draftId!),
    enabled: !!draftId,
    staleTime: 0,
  });
  const company = useQuery(companyQuery(draft.data?.companyId ?? selected?.id ?? ''));
  const products = useQuery(productsQuery);
  const dictionaries = useQuery(dictionariesQuery);
  if (
    products.isPending ||
    dictionaries.isPending ||
    company.isPending ||
    (draftId && draft.isPending)
  )
    return <LoadingState />;
  const failure = [products, dictionaries, company, ...(draftId ? [draft] : [])].find(
    (query) => query.isError,
  );
  if (failure) return <ErrorState error={failure.error} retry={() => void failure.refetch()} />;
  if (draft.data && draft.data.status !== 'DRAFT')
    return <Navigate to={`/applications/${draft.data.id}`} replace />;
  if (!company.data || !products.data || !dictionaries.data)
    return <ErrorState error={new Error('Не удалось загрузить анкету.')} />;
  const productCode = draft.data?.productCode ?? initialWorkspace.applicationDraft.productCode;
  const product = products.data.find((item) => item.code === productCode);
  if (!product)
    return (
      <section className="panel">
        <h1>Выберите кредитный продукт</h1>
        <p>Сначала сравните условия, затем переходите к анкете.</p>
        <Button href="/credit-products">
          К продуктам
        </Button>
      </section>
    );
  return (
    <ApplicationForm
      key={`${company.data.id}:${draftId ?? product.code}`}
      company={company.data}
      product={product}
      dictionaries={dictionaries.data}
      draft={draft.data}
    />
  );
}
function ApplicationForm({
  company,
  product,
  dictionaries,
  draft,
}: {
  company: Company;
  product: CreditProduct;
  dictionaries: Dictionaries;
  draft?: CreditApplication;
}) {
  const [workspace] = useState(() => {
    const stored = getWorkspace(company.id);
    return draft && stored.applicationId !== draft.id ? emptyWorkspace() : stored;
  });
  const [step, setStep] = useState(workspace.applicationStep);
  const [error, setError] = useState<unknown>(null);
  const applicationId = useRef(draft?.id ?? workspace.applicationId);
  const heading = useRef<HTMLHeadingElement>(null);
  const navigate = useNavigate();
  const client = useQueryClient();
  const form = useForm<ApplicationFormData>({
    resolver: zodResolver(schemaForProduct(product)),
    defaultValues: {
      productCode: product.code,
      purpose: 'WORKING_CAPITAL',
      requestedAmount: product.minAmount,
      term: product.maxTerm,
      termUnit: product.termUnit,
      fundingMode: product.fundingModes[0],
      repaymentType: product.repaymentTypes[0],
      subscription: false,
      annualRevenue: '',
      annualExpenses: '',
      bankMonthlyTurnover: '',
      monthlyDebtPayment: '',
      ...workspace.selection,
      ...(draft
        ? {
            productCode: draft.productCode ?? product.code,
            purpose: draft.purpose ?? 'WORKING_CAPITAL',
            requestedAmount: draft.requestedAmount ?? product.minAmount,
            term: draft.term ?? product.maxTerm,
            termUnit: draft.termUnit ?? product.termUnit,
            fundingMode: draft.fundingMode ?? product.fundingModes[0],
            repaymentType: draft.repaymentType ?? product.repaymentTypes[0],
            subscription: draft.subscription ?? false,
            annualRevenue: draft.financials?.annualRevenue ?? '',
            annualExpenses: draft.financials?.annualExpenses ?? '',
            bankMonthlyTurnover: draft.financials?.bankMonthlyTurnover ?? '',
            monthlyDebtPayment: draft.financials?.monthlyDebtPayment ?? '',
          }
        : {}),
      ...workspace.applicationDraft,
      companyId: company.id,
    },
  });
  useEffect(() => {
    const subscription = form.watch(() => {
      try {
        saveWorkspace({ applicationDraft: form.getValues() }, company.id);
        setError(null);
      } catch (err) {
        setError(err);
      }
    });
    return () => subscription.unsubscribe();
  }, [form, company.id]);
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0 });
  }, [step]);
  const showServerError = (err: unknown) => {
    setError(err);
    if (!(err instanceof ApiError)) return;
    const fieldNames = Object.keys(form.getValues());
    let earliestStep = 3;
    for (const issue of err.fields) {
      const name = issue.field.replace(/^financials\./, '');
      if (!fieldNames.includes(name)) continue;
      const field = name as FieldPath<ApplicationFormData>;
      form.setError(field, { type: 'server', message: issue.message });
      const index = stepFields.findIndex((fields) => fields.includes(field));
      if (index >= 0) earliestStep = Math.min(earliestStep, index);
    }
    if (earliestStep < 3) setStep(earliestStep);
  };
  const save = async (values: ApplicationFormData) => {
    const data = toApplicationRequest(values);
    if (!applicationId.current) {
      const app = await creditApi.createApplication({
        companyId: company.id,
        productCode: product.code,
        subscription: values.subscription,
      });
      applicationId.current = app.id;
      saveWorkspace({ applicationId: app.id, applicationDraft: values }, company.id);
    }
    const { companyId: _companyId, ...fields } = data;
    const patch: ApplicationPatchRequest = fields;
    patch.financials = Object.fromEntries(
      (
        ['annualRevenue', 'annualExpenses', 'bankMonthlyTurnover', 'monthlyDebtPayment'] as const
      ).map((field) => [field, values[field] === '' ? null : data.financials?.[field]]),
    );
    if (_companyId !== company.id)
      throw new Error('Компания черновика изменилась. Откройте анкету заново.');
    await creditApi.updateApplication(applicationId.current, patch);
    return applicationId.current;
  };
  const next = useMutation({
    mutationFn: async () => {
      if (!(await form.trigger(stepFields[step], { shouldFocus: true }))) return false;
      await save(form.getValues());
      return true;
    },
    onSuccess: (valid) => {
      if (valid) {
        saveWorkspace({ applicationStep: step + 1 }, company.id);
        setStep(step + 1);
        setError(null);
      }
    },
    onError: showServerError,
  });
  const submit = useMutation({
    mutationFn: async (values: ApplicationFormData) => {
      const id = await save(values);
      return creditApi.submitApplication(id);
    },
    onSuccess: (app) => {
      clearApplicationDraft(company.id);
      client.setQueryData(['applications', app.id], app);
      void client.invalidateQueries({ queryKey: ['applications', 'list'] });
      navigate(`/applications/${app.id}`, { replace: true });
    },
    onError: showServerError,
  });
  const busy = next.isPending || submit.isPending;
  const send = form.handleSubmit(
    (data) => submit.mutate(data),
    (errors) => {
      const index = stepFields.findIndex((fields) => fields.some((field) => errors[field]));
      if (index >= 0) setStep(index);
    },
  );
  return (
    <div className={styles.formPage}>
      <div className={styles.formHeader}>
        <Link className="back" to={`/credit-products/${product.code}`}>
          ← К продукту
        </Link>
        <span className="muted">{product.name}</span>
      </div>
      <section className={`panel ${styles.formPanel}`}>
        <StepProgress labels={labels} current={step} />
        <h1 className={styles.formTitle} tabIndex={-1} ref={heading}>
          {step === 3 ? 'Всё верно? Проверьте заявку' : labels[step]}
        </h1>
        <FormProvider {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (busy) return;
              if (step === 3) void send();
              else next.mutate();
            }}
          >
            {step === 0 && <CompanyFields company={company} />}
            {step === 1 && <CreditFields product={product} dictionaries={dictionaries} />}
            {step === 2 && <FinanceFields />}
            {step === 3 && (
              <ApplicationSummary
                data={toApplicationRequest(form.getValues())}
                productName={product.name}
                companyName={company.name}
                financials
              />
            )}
            {error !== null && <ErrorState error={error} />}
            <div className="actions">
              {step > 0 && (
                <Button
                  disabled={busy}
                  onClick={() => {
                    saveWorkspace({ applicationStep: step - 1 }, company.id);
                    setStep(step - 1);
                  }}
                >
                  Назад
                </Button>
              )}
              <Button view="primary" type="submit" loading={busy}>
                {step === 3 ? 'Отправить заявку' : 'Далее'}
              </Button>
            </div>
          </form>
        </FormProvider>
      </section>
      <p className={styles.disclaimer}>Черновик сохраняется отдельно для каждой компании.</p>
    </div>
  );
}
