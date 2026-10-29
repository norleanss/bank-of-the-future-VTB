import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { creditApi, isMockApi } from '../../shared/api';
import type { ApplicationStatus } from '../../shared/types/models';
import { ErrorState } from '../../shared/ui/Feedback';
import styles from './status.module.css';
import { Button } from '../../shared/ui/Button';
import { Select } from '../../shared/ui/Select';
const scenarios: { key: ApplicationStatus; content: string }[] = [
  { key: 'APPROVED', content: 'Кредит одобрен' },
  { key: 'COUNTER_OFFER', content: 'Другие условия' },
  { key: 'NEED_INFO', content: 'Нужны дополнительные данные' },
  { key: 'REJECTED', content: 'Отказ' },
  { key: 'MANUAL_REVIEW', content: 'Ручная проверка' },
  { key: 'OFFER_EXPIRED', content: 'Предложение истекло' },
];
export function DemoDecision({ applicationId }: { applicationId: string }) {
  const [scenario, setScenario] = useState<ApplicationStatus>('APPROVED');
  const client = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => {
      if (!creditApi.setDemoDecision) throw new Error('Сценарии доступны только в mock-режиме.');
      return creditApi.setDemoDecision(applicationId, scenario);
    },
    onSuccess: (app) => {
      client.setQueryData(['applications', app.id], app);
      void client.invalidateQueries({ queryKey: ['applications', 'list'] });
    },
  });
  if (!isMockApi) return null;
  return (
    <details className={styles.demo}>
      <summary>Демонстрация состояний</summary>
      <p>Локальные сценарии для проверки интерфейса. С реальным API решения приходят от банка.</p>
      <div className="fields">
        <Select
          label="Сценарий решения"
          options={scenarios}
          selected={scenario}
          onChange={({ selected }) => {
            const option = scenarios.find((item) => item.key === selected?.key);
            if (option) setScenario(option.key);
          }}
        />
        <Button size={40} loading={mutation.isPending} onClick={() => mutation.mutate()}>
          Показать результат
        </Button>
        {mutation.isError && <ErrorState error={mutation.error} />}
      </div>
    </details>
  );
}
