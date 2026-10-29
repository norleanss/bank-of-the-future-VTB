import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type TextareaHTMLAttributes,
} from 'react';
import { cx } from '../lib/cx';
import styles from './controls.module.css';

type FieldProps = { label: string; hint?: ReactNode; error?: ReactNode };

function FieldNote({ id, hint, error }: { id: string } & Omit<FieldProps, 'label'>) {
  if (error) return <p id={id} className={styles.error}>{error}</p>;
  if (hint) return <p id={id} className={styles.hint}>{hint}</p>;
  return null;
}

export const Input = forwardRef<
  HTMLInputElement,
  FieldProps & Omit<InputHTMLAttributes<HTMLInputElement>, 'size'>
>(function Input({ label, hint, error, id, className, ...rest }, ref) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const noteId = `${inputId}-note`;
  return (
    <div className={cx(styles.field, Boolean(error) && styles.invalid, className)}>
      <div className={cx(styles.control, rest.readOnly && styles.readOnly)}>
        <input
          ref={ref}
          id={inputId}
          placeholder=" "
          aria-invalid={error ? true : undefined}
          aria-describedby={error || hint ? noteId : undefined}
          {...rest}
          className={styles.input}
        />
        <label htmlFor={inputId} className={styles.label}>
          {label}
        </label>
      </div>
      <FieldNote id={noteId} hint={hint} error={error} />
    </div>
  );
});

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  FieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ label, hint, error, id, className, rows = 3, ...rest }, ref) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const noteId = `${inputId}-note`;
  return (
    <div className={cx(styles.field, Boolean(error) && styles.invalid, className)}>
      <div className={cx(styles.control, styles.multiline)}>
        <textarea
          ref={ref}
          id={inputId}
          rows={rows}
          placeholder=" "
          aria-invalid={error ? true : undefined}
          aria-describedby={error || hint ? noteId : undefined}
          {...rest}
          className={styles.input}
        />
        <label htmlFor={inputId} className={styles.label}>
          {label}
        </label>
      </div>
      <FieldNote id={noteId} hint={hint} error={error} />
    </div>
  );
});
