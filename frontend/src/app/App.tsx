import { lazy, Suspense } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { Layout } from './Layout';
import { LoadingState } from '../shared/ui/Feedback';
import { NotFoundPage } from '../pages/NotFoundPage';

const LoginPage = lazy(() =>
  import('../pages/LoginPage').then((module) => ({
    default: module.LoginPage,
  })),
);
const HomePage = lazy(() =>
  import('../pages/HomePage').then((module) => ({ default: module.HomePage })),
);
const SelectionPage = lazy(() =>
  import('../pages/SelectionPage').then((module) => ({
    default: module.SelectionPage,
  })),
);
const ProductsPage = lazy(() =>
  import('../pages/ProductsPage').then((module) => ({
    default: module.ProductsPage,
  })),
);
const ProductPage = lazy(() =>
  import('../pages/ProductPage').then((module) => ({
    default: module.ProductPage,
  })),
);
const NewApplicationPage = lazy(() =>
  import('../pages/NewApplicationPage').then((module) => ({
    default: module.NewApplicationPage,
  })),
);
const ApplicationPage = lazy(() =>
  import('../pages/ApplicationPage').then((module) => ({
    default: module.ApplicationPage,
  })),
);
const ProfilePage = lazy(() =>
  import('../pages/ProfilePage').then((module) => ({
    default: module.ProfilePage,
  })),
);
const NotificationsPage = lazy(() =>
  import('../pages/NotificationsPage').then((module) => ({ default: module.NotificationsPage })),
);
const BackofficePage = lazy(() =>
  import('../pages/BackofficePage').then((module) => ({
    default: module.BackofficePage,
  })),
);
const BackofficeApplicationPage = lazy(() =>
  import('../pages/BackofficeApplicationPage').then((module) => ({
    default: module.BackofficeApplicationPage,
  })),
);

export function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<LoadingState />}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<Layout />}>
            <Route index element={<HomePage />} />
            <Route path="credit-selection" element={<SelectionPage />} />
            <Route path="credit-products" element={<ProductsPage />} />
            <Route path="credit-products/:id" element={<ProductPage />} />
            <Route path="applications/new" element={<NewApplicationPage />} />
            <Route path="applications/:id" element={<ApplicationPage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="backoffice" element={<BackofficePage />} />
            <Route path="backoffice/applications/:id" element={<BackofficeApplicationPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
