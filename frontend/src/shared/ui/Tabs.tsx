import { useRef, type KeyboardEvent } from 'react';
import { cx } from '../lib/cx';
import styles from './controls.module.css';

export function Tabs<T extends string>({
  label,
  tabs,
  selected,
  onChange,
}: {
  label: string;
  tabs: readonly { id: T; title: string }[];
  selected: T;
  onChange: (id: T) => void;
}) {
  const list = useRef<HTMLDivElement>(null);
  const move = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
    if (!step) return;
    event.preventDefault();
    const index = tabs.findIndex((tab) => tab.id === selected);
    const next = tabs[(index + step + tabs.length) % tabs.length]!;
    onChange(next.id);
    list.current?.querySelector<HTMLButtonElement>(`[data-tab="${next.id}"]`)?.focus();
  };
  return (
    <div ref={list} role="tablist" aria-label={label} className={styles.tabs} onKeyDown={move}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          data-tab={tab.id}
          aria-selected={tab.id === selected}
          tabIndex={tab.id === selected ? 0 : -1}
          className={cx(styles.tab, tab.id === selected && styles.tabSelected)}
          onClick={() => onChange(tab.id)}
        >
          {tab.title}
        </button>
      ))}
    </div>
  );
}
