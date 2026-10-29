import type { Company } from '../../shared/types/models';
import { date } from '../../shared/lib/format';
import styles from './form.module.css';
import { Input } from '../../shared/ui/Field';
export function CompanyFields({ company }: { company: Company }) {
  return (
    <div className={styles.formFields}>
      <Input label="ИНН" value={company.inn} readOnly />
      <Input label="Название организации" value={company.name} readOnly />
      <p className={styles.fullWidth}>
        Компания из вашего профиля. Дата регистрации — {date(company.registrationDate)}.
        {company.status !== 'ACTIVE' && (
          <strong>
            {' '}
            Компания прекращает или прекратила деятельность; это может повлиять на решение.
          </strong>
        )}
      </p>
    </div>
  );
}
