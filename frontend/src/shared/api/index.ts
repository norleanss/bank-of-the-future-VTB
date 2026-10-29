import type { CreditApi } from './contract';
import { mockApi } from '../mocks/api';
import { httpApi } from './http';

export const isMockApi = import.meta.env.VITE_API_MODE !== 'http';
export const creditApi: CreditApi = isMockApi ? mockApi : httpApi;
