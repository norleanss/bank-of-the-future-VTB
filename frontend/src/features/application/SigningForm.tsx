import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { creditApi } from '../../shared/api';
import type { CreditApplication } from '../../shared/types/models';
import { ErrorState } from '../../shared/ui/Feedback';
import { Button } from '../../shared/ui/Button';
import { Input } from '../../shared/ui/Field';
const schema = z.object({
  code: z.string().regex(/^\d{4}$/, 'Введите код из четырёх цифр'),
});
export function SigningForm({
  id,
  onSuccess,
}: {
  id: string;
  onSuccess: (app: CreditApplication) => void;
}) {
  const [issued, setIssued] = useState<{
    expiresAt: string;
    demoCode?: string;
  }>();
  const form = useForm<{ code: string }>({
    resolver: zodResolver(schema),
    defaultValues: { code: '' },
  });
  const request = useMutation({
    mutationFn: () => creditApi.requestSigningCode(id),
    onSuccess: (data) => {
      setIssued(data);
      form.reset();
    },
  });
  const confirm = useMutation({
    mutationFn: ({ code }: { code: string }) => creditApi.confirmSigning(id, code),
    onSuccess,
  });
  return (
    <section>
      <p>Подтвердите подписание договора кодом. В демо он появится прямо здесь.</p>
      <Button
        loading={request.isPending}
        disabled={confirm.isPending}
        onClick={() => request.mutate()}
      >
        {issued ? 'Получить новый код' : 'Получить код для подписания'}
      </Button>
      {issued && (
        <form
          className="fields"
          noValidate
          onSubmit={form.handleSubmit((data) => confirm.mutate(data))}
          style={{ marginTop: 20 }}
        >
          {issued.demoCode && (
            <p className="notice">
              Демо-код: <strong data-testid="signing-code">{issued.demoCode}</strong>
            </p>
          )}
          <p className="muted">
            Код действует до{' '}
            {new Date(issued.expiresAt).toLocaleTimeString('ru-RU', {
              hour: '2-digit',
              minute: '2-digit',
            })}
            .
          </p>
          <Controller
            name="code"
            control={form.control}
            render={({ field, fieldState }) => (
              <Input
                {...field}
                label="Код подтверждения"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={4}
                error={fieldState.error?.message}
              />
            )}
          />
          <Button type="submit" view="primary" loading={confirm.isPending}>
            Подписать договор
          </Button>
        </form>
      )}
      {request.isError && <ErrorState error={request.error} />}
      {confirm.isError && <ErrorState error={confirm.error} />}
    </section>
  );
}
