import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cx } from '../lib/cx';
import styles from './controls.module.css';

type CommonProps = {
  view?: 'primary' | 'secondary' | 'text';
  size?: 40 | 48 | 56;
  block?: boolean;
  loading?: boolean;
  className?: string;
  children: ReactNode;
};

type ButtonProps =
  | (CommonProps & { href: string })
  | (CommonProps &
      Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className' | 'children'> & {
        href?: undefined;
      });

export function Button({
  view = 'secondary',
  size = 56,
  block,
  loading,
  className,
  children,
  ...rest
}: ButtonProps) {
  const classes = cx(
    styles.button,
    styles[view],
    styles[`size${size}`],
    block && styles.block,
    loading && styles.loading,
    className,
  );
  const content = (
    <>
      {loading && <span className={styles.buttonSpinner} aria-hidden="true" />}
      <span className={styles.buttonLabel}>{children}</span>
    </>
  );
  if (rest.href !== undefined)
    return (
      <Link to={rest.href} className={classes}>
        {content}
      </Link>
    );
  const { type = 'button', disabled, ...native } = rest;
  return (
    <button
      {...native}
      type={type}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
    >
      {content}
    </button>
  );
}
