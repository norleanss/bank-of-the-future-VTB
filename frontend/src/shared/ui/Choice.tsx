import type { ReactNode } from 'react';
import { cx } from '../lib/cx';
import { CheckIcon } from './icons';
import styles from './controls.module.css';

export function Checkbox({
  label,
  checked,
  onChange,
  name,
  className,
}: {
  label: ReactNode;
  checked: boolean;
  onChange: (checked: boolean) => void;
  name?: string;
  className?: string;
}) {
  return (
    <label className={cx(styles.choice, className)}>
      <input
        type="checkbox"
        className={styles.choiceInput}
        name={name}
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span className={styles.checkbox} aria-hidden="true">
        <CheckIcon />
      </span>
      <span>{label}</span>
    </label>
  );
}

export function Radio({
  label,
  checked,
  onChange,
  name,
  block,
  className,
}: {
  label: ReactNode;
  checked: boolean;
  onChange: () => void;
  name?: string;
  block?: boolean;
  className?: string;
}) {
  return (
    <label className={cx(styles.choice, block && styles.choiceBlock, className)}>
      <input
        type="radio"
        className={styles.choiceInput}
        name={name}
        checked={checked}
        onChange={onChange}
      />
      <span className={styles.radio} aria-hidden="true" />
      <span>{label}</span>
    </label>
  );
}
