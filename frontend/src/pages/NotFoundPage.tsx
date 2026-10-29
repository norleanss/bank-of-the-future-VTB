import { Link } from 'react-router-dom';
export function NotFoundPage() {
  return (
    <section className="panel">
      <div className="eyebrow">404</div>
      <h1>Такой страницы нет</h1>
      <p>Проверьте адрес или вернитесь в кабинет.</p>
      <Link to="/">На главную</Link>
    </section>
  );
}
