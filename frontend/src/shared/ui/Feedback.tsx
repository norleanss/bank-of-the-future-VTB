import { errorMessage } from '../lib/format';
import controls from './controls.module.css';
import styles from './ui.module.css';
import { Button } from './Button';

export function LoadingState({ text = 'Загружаем данные…' }: { text?: string }) {
  return (
    <div className={styles.feedback} role="status">
      <span className={controls.spinner} aria-hidden="true" />
      <p>{text}</p>
    </div>
  );
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div className={styles.error} role="alert">
      <p>{errorMessage(error)}</p>
      {retry && (
        <Button size={40} onClick={retry}>
          Повторить
        </Button>
      )}
    </div>
  );
}
