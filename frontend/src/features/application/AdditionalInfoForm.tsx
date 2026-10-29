import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { creditApi } from '../../shared/api';
import { ApiError } from '../../shared/api/errors';
import type {
  ApplicationPatchRequest,
  CreditApplication,
  DocumentType,
  Financials,
  UploadedDocument,
} from '../../shared/types/models';
import { dictionariesQuery } from '../../app/queries';
import { normalizeMoney } from '../../shared/lib/money';
import { ErrorState, LoadingState } from '../../shared/ui/Feedback';
import { documentLabels, infoFieldLabels as labels } from '../../shared/lib/applicationFields';
import { Button } from '../../shared/ui/Button';
import { Input, Textarea } from '../../shared/ui/Field';
import { Select } from '../../shared/ui/Select';
import { FileButton } from '../../shared/ui/FileButton';
const fieldKey = (path: string) => path.replaceAll('.', '__');
export function AdditionalInfoForm({
  application: app,
  onSuccess,
}: {
  application: CreditApplication;
  onSuccess: (app: CreditApplication) => void;
}) {
  const request = app.openInfoRequest;
  const client = useQueryClient();
  const dictionaries = useQuery(dictionariesQuery);
  const documents = useQuery({
    queryKey: ['documents', app.id],
    queryFn: () => creditApi.getDocuments(app.id),
  });
  const [uploaded, setUploaded] = useState<UploadedDocument[]>([]);
  const [uploadType, setUploadType] = useState<DocumentType>(
    request?.requestedDocuments[0] ?? 'OTHER',
  );
  const form = useForm<Record<string, string>>({
    defaultValues: { comment: '' },
  });
  const upload = useMutation({
    mutationFn: (file: File) => {
      if (file.size === 0 || file.size > 10 * 1024 * 1024)
        throw new Error('Выберите непустой файл размером до 10 МБ.');
      if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type))
        throw new Error('Поддерживаются PDF, JPG и PNG.');
      if ((documents.data?.length ?? 0) >= 20)
        throw new Error('К заявке можно загрузить не больше 20 документов.');
      return creditApi.uploadDocument(app.id, file, uploadType);
    },
    onSuccess: (doc) => {
      setUploaded((previous) => [...previous, doc]);
      void client.invalidateQueries({ queryKey: ['documents', app.id] });
    },
  });
  const send = useMutation({
    mutationFn: async (values: Record<string, string>) => {
      if (!request) throw new Error('Запрос уже закрыт.');
      const availableDocuments = [
        ...new Map(
          [...(documents.data ?? []), ...uploaded]
            .filter((doc) => !doc.infoRequestId)
            .map((doc) => [doc.id, doc]),
        ).values(),
      ];
      if (
        request.requestedDocuments.some(
          (type) => !availableDocuments.some((doc) => doc.documentType === type),
        )
      )
        throw new Error('Загрузите документ каждого запрошенного типа.');
      const patch: ApplicationPatchRequest = {};
      for (const path of request.requestedFields) {
        const value = values[fieldKey(path)] ?? '';
        if (path.startsWith('financials.')) {
          const key = path.slice(11) as keyof Financials;
          patch.financials = {
            ...patch.financials,
            [key]: normalizeMoney(value),
          };
        } else Reflect.set(patch, path, value);
      }
      if (Object.keys(patch).length) await creditApi.updateApplication(app.id, patch);
      return creditApi.respondInfo(app.id, request.id, {
        comment: values.comment,
        documentIds: availableDocuments.map((doc) => doc.id),
      });
    },
    onSuccess,
    onError: (error) => {
      if (error instanceof ApiError)
        for (const issue of error.fields)
          form.setError(fieldKey(issue.field), { message: issue.message });
    },
  });
  if (!request) return <p>Обновите заявку, чтобы увидеть запрос банка.</p>;
  if (dictionaries.isPending || documents.isPending) return <LoadingState />;
  if (dictionaries.isError || documents.isError)
    return (
      <ErrorState
        error={dictionaries.error ?? documents.error}
        retry={() => {
          void dictionaries.refetch();
          void documents.refetch();
        }}
      />
    );
  const unsupported = request.requestedFields.filter((field) => !labels[field]);
  const optionsFor = (path: string) =>
    path === 'purpose'
      ? dictionaries.data.purposes
      : path === 'fundingMode'
        ? dictionaries.data.fundingModes
        : path === 'repaymentType'
          ? dictionaries.data.repaymentTypes
          : undefined;
  return (
    <form
      className="fields"
      noValidate
      onSubmit={form.handleSubmit((values) => send.mutate(values))}
    >
      <p>{request.message}</p>
      {unsupported.length > 0 && (
        <p role="alert">
          Банк запросил поля, недоступные в этой версии анкеты: {unsupported.join(', ')}. Обратитесь
          к команде проекта.
        </p>
      )}
      {request.requestedFields
        .filter((path) => labels[path])
        .map((path) => {
          const options = optionsFor(path);
          return (
            <Controller
              key={path}
              name={fieldKey(path)}
              control={form.control}
              defaultValue={String(
                path.startsWith('financials.')
                  ? (app.financials?.[path.slice(11) as keyof Financials] ?? '')
                  : (Reflect.get(app, path) ?? ''),
              )}
              rules={{
                required: 'Заполните запрошенное поле',
                validate: (value) =>
                  path.startsWith('financials.')
                    ? /^\d{1,15}\.\d{2}$/.test(normalizeMoney(value)) || 'Введите корректную сумму'
                    : true,
              }}
              render={({ field, fieldState }) =>
                options ? (
                  <Select
                    label={labels[path] ?? path}
                    ref={field.ref}
                    selected={field.value}
                    options={options.map((item) => ({
                      key: item.code,
                      content: item.label,
                    }))}
                    onChange={({ selected }) => field.onChange(selected?.key)}
                    error={fieldState.error?.message}
                  />
                ) : (
                  <Input
                    {...field}
                    label={labels[path] ?? path}
                    inputMode="decimal"
                    error={fieldState.error?.message}
                  />
                )
              }
            />
          );
        })}
      {app.availableActions.includes('UPLOAD_DOCUMENT') && (
        <>
          <p className="muted">
            PDF, JPG или PNG, до 10 МБ.{' '}
            {request.requestedDocuments.length
              ? 'Загрузите документы каждого запрошенного типа.'
              : 'При необходимости приложите документ.'}
          </p>
          {request.requestedDocuments.length > 0 && (
            <ul>
              {request.requestedDocuments.map((type) => (
                <li key={type}>{documentLabels[type]}</li>
              ))}
            </ul>
          )}
          <Select
            label="Тип документа"
            options={Object.entries(documentLabels).map(([key, content]) => ({
              key,
              content,
            }))}
            selected={uploadType}
            onChange={({ selected }) => {
              if (selected && selected.key in documentLabels)
                setUploadType(selected.key as DocumentType);
            }}
          />
          <FileButton
            accept=".pdf,.jpg,.jpeg,.png"
            disabled={upload.isPending || send.isPending}
            onSelect={(file) => upload.mutate(file)}
          >
            Прикрепить документ
          </FileButton>
          {upload.isPending && <p role="status">Загружаем документ…</p>}
          {(documents.data ?? [])
            .filter((doc) => !doc.infoRequestId)
            .map((doc) => (
              <p key={doc.id}>
                ✓ {documentLabels[doc.documentType]}: {doc.fileName}
              </p>
            ))}
          {upload.isError && <ErrorState error={upload.error} />}
        </>
      )}
      <Controller
        name="comment"
        control={form.control}
        rules={{
          maxLength: { value: 2000, message: 'Не больше 2000 символов' },
        }}
        render={({ field, fieldState }) => (
          <Textarea
            {...field}
            label="Комментарий для банка"
            id="info-response-comment"
            aria-label="Комментарий для банка"
            error={fieldState.error?.message}
          />
        )}
      />
      {send.isError && <ErrorState error={send.error} />}
      <Button
        type="submit"
        view="primary"
        loading={send.isPending}
        disabled={unsupported.length > 0 || upload.isPending}
      >
        Отправить данные
      </Button>
    </form>
  );
}
