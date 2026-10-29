import {
  forwardRef,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { cx } from '../lib/cx';
import { ChevronDownIcon } from './icons';
import styles from './controls.module.css';

export type SelectOption = { key: string; content: ReactNode };

type SelectProps = {
  label: string;
  options: readonly SelectOption[];
  selected?: string | null;
  onChange: (payload: { selected: SelectOption | null }) => void;
  valueRenderer?: (payload: { selected?: SelectOption }) => ReactNode;
  name?: string;
  onBlur?: () => void;
  hint?: ReactNode;
  error?: ReactNode;
  className?: string;
};

export const Select = forwardRef<HTMLButtonElement, SelectProps>(function Select(
  { label, options, selected, onChange, valueRenderer, name, onBlur, hint, error, className },
  ref,
) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const [open, setOpen] = useState(false);
  const selectedIndex = options.findIndex((option) => option.key === selected);
  const [active, setActive] = useState(Math.max(selectedIndex, 0));
  const current = selectedIndex >= 0 ? options[selectedIndex] : undefined;
  const value = valueRenderer ? valueRenderer({ selected: current }) : current?.content;
  const noteId = `${id}-note`;

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  useEffect(() => {
    if (open) list.current?.children[active]?.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  const show = () => {
    setActive(Math.max(selectedIndex, 0));
    setOpen(true);
  };
  const choose = (index: number) => {
    const option = options[index];
    setOpen(false);
    if (option && option.key !== selected) onChange({ selected: option });
  };
  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const last = options.length - 1;
    const keys: Record<string, () => void> = {
      ArrowDown: () => (open ? setActive(Math.min(active + 1, last)) : show()),
      ArrowUp: () => (open ? setActive(Math.max(active - 1, 0)) : show()),
      Home: () => open && setActive(0),
      End: () => open && setActive(last),
      Enter: () => (open ? choose(active) : show()),
      ' ': () => (open ? choose(active) : show()),
      Escape: () => setOpen(false),
    };
    if (event.key === 'Tab') setOpen(false);
    const action = keys[event.key];
    if (!action || (event.key === 'Escape' && !open)) return;
    event.preventDefault();
    action();
  };

  return (
    <div
      ref={root}
      className={cx(styles.field, styles.select, Boolean(error) && styles.invalid, className)}
    >
      <button
        ref={ref}
        type="button"
        name={name}
        role="combobox"
        aria-label={label}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${id}-list` : undefined}
        aria-activedescendant={open ? `${id}-option-${active}` : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? noteId : undefined}
        className={cx(styles.control, styles.selectButton, current && styles.filled)}
        onClick={() => (open ? setOpen(false) : show())}
        onKeyDown={onKeyDown}
        onBlur={onBlur}
      >
        <span className={styles.label}>{label}</span>
        <span className={styles.selectValue}>{value}</span>
        <ChevronDownIcon className={styles.chevron} aria-hidden="true" />
      </button>
      {open && (
        <ul ref={list} id={`${id}-list`} role="listbox" aria-label={label} className={styles.menu}>
          {options.map((option, index) => (
            <li
              key={option.key}
              id={`${id}-option-${index}`}
              role="option"
              aria-selected={option.key === selected}
              className={cx(styles.option, index === active && styles.optionActive)}
              onMouseDown={(event) => event.preventDefault()}
              onMouseEnter={() => setActive(index)}
              onClick={() => choose(index)}
            >
              {option.content}
            </li>
          ))}
        </ul>
      )}
      {error ? (
        <p id={noteId} className={styles.error}>
          {error}
        </p>
      ) : (
        hint && (
          <p id={noteId} className={styles.hint}>
            {hint}
          </p>
        )
      )}
    </div>
  );
});
