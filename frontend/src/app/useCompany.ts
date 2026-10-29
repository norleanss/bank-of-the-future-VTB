import { useQuery } from '@tanstack/react-query';
import { getSelectedCompanyId } from '../shared/lib/storage';
import { sessionQuery } from './queries';

export function useCompany() {
  const session = useQuery(sessionQuery);
  const selected = getSelectedCompanyId();
  return (
    session.data?.user.companies?.find((company) => company.id === selected) ??
    session.data?.user.companies?.[0]
  );
}
