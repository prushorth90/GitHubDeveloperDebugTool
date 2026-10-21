import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { getRepositories, getRepositoryStatistics, repositoryErrorMessage } from './repositories';

afterEach(() => vi.restoreAllMocks());

describe('repository API client', () => {
  it('uses the discovery envelope, bounded page size, opaque cursor and cancellation signal', async () => {
    const page = { items: [], nextToken: 'next/page+token' };
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: { data: page } });
    const signal = new AbortController().signal;
    expect(await getRepositories(null, signal)).toBe(page);
    expect(get).toHaveBeenLastCalledWith('/api/repositories', {
      params: { limit: 12, nextToken: undefined }, signal,
    });
    await getRepositories(page.nextToken, signal);
    expect(get).toHaveBeenLastCalledWith('/api/repositories', {
      params: { limit: 12, nextToken: page.nextToken }, signal,
    });
  });

  it('requests persisted statistics by numeric repository ID without calling GitHub', async () => {
    const summary = { totalRuns: 9 };
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: { data: summary } });
    const signal = new AbortController().signal;
    expect(await getRepositoryStatistics(42, signal)).toBe(summary);
    expect(get).toHaveBeenCalledExactlyOnceWith('/api/repositories/42/statistics', { signal });
  });

  it('propagates API failures rather than returning success-shaped defaults', async () => {
    const error = new Error('Unavailable');
    vi.spyOn(api, 'get').mockRejectedValue(error);
    await expect(getRepositories(null)).rejects.toBe(error);
    await expect(getRepositoryStatistics(1)).rejects.toBe(error);
  });

  it('uses standard backend error messages, HTTP status fallbacks and network guidance', () => {
    const config = { headers: new AxiosHeaders() };
    const response = { data: { message: 'Analysis database is temporarily unavailable' }, status: 503,
      statusText: 'Unavailable', headers: new AxiosHeaders(), config };
    expect(repositoryErrorMessage(new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, null, response)))
      .toBe('Analysis database is temporarily unavailable');
    expect(repositoryErrorMessage(new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, null, {
      ...response, data: {},
    }))).toContain('HTTP 503');
    expect(repositoryErrorMessage(new AxiosError('Network Error'))).toContain('Cannot reach the API');
  });
});
