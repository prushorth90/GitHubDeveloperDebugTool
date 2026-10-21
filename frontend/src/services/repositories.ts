import axios from 'axios';
import { api } from './api';
import type { ApiError, ApiPage, ApiResponse, Repository, RepositoryStatistics } from '../types/repositories';

export async function getRepositories(nextToken: string | null, signal?: AbortSignal): Promise<ApiPage<Repository>> {
  const { data } = await api.get<ApiResponse<ApiPage<Repository>>>('/api/repositories', {
    params: { limit: 12, nextToken: nextToken ?? undefined },
    signal,
  });
  return data.data;
}

export async function getRepositoryStatistics(id: number, signal?: AbortSignal): Promise<RepositoryStatistics> {
  const { data } = await api.get<ApiResponse<RepositoryStatistics>>(`/api/repositories/${id}/statistics`, { signal });
  return data.data;
}

export function repositoryErrorMessage(error: Error): string {
  if (axios.isAxiosError<Partial<ApiError>>(error)) {
    if (!error.response) return 'Cannot reach the API. Check your connection and backend configuration, then retry.';
    const message = error.response.data?.message;
    return typeof message === 'string' && message.trim()
      ? message
      : `The API request failed (HTTP ${error.response.status}). Please retry.`;
  }
  return error.message;
}
