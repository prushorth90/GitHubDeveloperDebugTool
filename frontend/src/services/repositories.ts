import axios from 'axios';
import { api } from './api';
import type { ApiError, ApiPage, ApiResponse, Repository, RepositoryStatistics, WorkflowAnalytics } from '../types/repositories';

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

export async function getWorkflowAnalytics(id: number, days: number, signal?: AbortSignal): Promise<WorkflowAnalytics> {
  const { data } = await api.get<ApiResponse<WorkflowAnalytics>>(`/api/repositories/${id}/analytics`, {
    params: { days }, signal,
  });
  const summary = data.data;
  const count = (value: number) => Number.isSafeInteger(value) && value >= 0;
  if (!summary || ![summary.totalRuns, summary.failedRuns, summary.recurringFailures, summary.successfulAnalyses].every(count)
      || !Array.isArray(summary.dailyRates)
      || !summary.dailyRates.every((day) => day && typeof day.date === 'string'
        && count(day.totalRuns) && count(day.failedRuns)
        && (day.failureRate === null || typeof day.failureRate === 'number'
          && Number.isFinite(day.failureRate) && day.failureRate >= 0 && day.failureRate <= 100))
      || !summary.failuresByCategory || typeof summary.failuresByCategory !== 'object'
      || Array.isArray(summary.failuresByCategory) || !Object.values(summary.failuresByCategory).every(count)) {
    throw new Error('The API returned invalid analytics data. Check that the backend supports historical analytics, then retry.');
  }
  return summary;
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
