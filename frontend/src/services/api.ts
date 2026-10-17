import axios from 'axios';

export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() || 'http://localhost:8080';

export const api = axios.create({
  baseURL: apiBaseUrl,
  timeout: 8000,
  headers: { Accept: 'application/json' },
});

export interface HealthResponse {
  status: string;
  service: string;
  timestamp: string;
}

export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const { data } = await api.get<unknown>('/api/health', { signal });
  if (
    typeof data !== 'object' || data === null ||
    !('status' in data) || typeof data.status !== 'string' ||
    !('service' in data) || typeof data.service !== 'string' ||
    !('timestamp' in data) || typeof data.timestamp !== 'string'
  ) {
    throw new Error('The backend returned an invalid health response.');
  }
  return { status: data.status, service: data.service, timestamp: data.timestamp };
}
