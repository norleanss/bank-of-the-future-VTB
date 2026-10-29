import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from '../shared/ui/Button';

export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack);
  }
  render() {
    if (this.state.error)
      return (
        <main className="container">
          <section className="panel">
            <h1>Не удалось открыть кабинет</h1>
            <p role="alert">{this.state.error.message}</p>
            <Button onClick={() => window.location.reload()}>Обновить страницу</Button>
          </section>
        </main>
      );
    return this.props.children;
  }
}
