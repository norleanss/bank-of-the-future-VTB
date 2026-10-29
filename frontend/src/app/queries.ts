import { queryOptions } from '@tanstack/react-query';
import { creditApi } from '../shared/api';
export const sessionQuery = queryOptions({
  queryKey: ['session'],
  queryFn: () => creditApi.getSession(),
  staleTime: Infinity,
});
export const productsQuery = queryOptions({
  queryKey: ['products'],
  queryFn: () => creditApi.getCreditProducts(),
  staleTime: 60_000,
});
export const dictionariesQuery = queryOptions({
  queryKey: ['dictionaries'],
  queryFn: () => creditApi.getDictionaries(),
  staleTime: Infinity,
});
export const companyQuery = (id: string) =>
  queryOptions({
    queryKey: ['companies', id],
    queryFn: () => creditApi.getCompany(id),
    enabled: !!id,
    staleTime: 60_000,
  });
export const applicationsQuery = (companyId: string, page = 0) =>
  queryOptions({
    queryKey: ['applications', 'list', companyId, page],
    queryFn: () => creditApi.getApplications(companyId || undefined, page),
    refetchInterval: 3000,
  });
