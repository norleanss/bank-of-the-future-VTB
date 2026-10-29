import controls from './controls.module.css';
import styles from './ui.module.css';

export function StepProgress({ labels, current }: { labels: readonly string[]; current: number }) {
  return (
    <div className={styles.progress}>
      <div className={styles.progressMeta}>
        <span>
          Шаг {current + 1} из {labels.length}
        </span>
        <span>{labels[current]}</span>
      </div>
      <div
        role="progressbar"
        aria-label="Прогресс заполнения"
        aria-valuemin={0}
        aria-valuemax={labels.length}
        aria-valuenow={current + 1}
        className={styles.progressTrack}
      >
        <div className={controls.progressTrack} aria-hidden="true">
          <div
            className={controls.progressFill}
            style={{ width: `${((current + 1) / labels.length) * 100}%` }}
          />
        </div>
      </div>
    </div>
  );
}
