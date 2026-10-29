import type { ReactNode } from 'react';
import { cx } from '../lib/cx';
import { PaperclipIcon } from './icons';
import styles from './controls.module.css';

export function FileButton({
  children,
  accept,
  disabled,
  onSelect,
}: {
  children: ReactNode;
  accept?: string;
  disabled?: boolean;
  onSelect: (file: File) => void;
}) {
  return (
    <label
      className={cx(styles.button, styles.secondary, styles.size48, styles.fileButton)}
      aria-disabled={disabled || undefined}
    >
      <PaperclipIcon aria-hidden="true" />
      <span className={styles.buttonLabel}>{children}</span>
      <input
        type="file"
        className={styles.fileInput}
        accept={accept}
        disabled={disabled}
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (file) onSelect(file);
        }}
      />
    </label>
  );
}
