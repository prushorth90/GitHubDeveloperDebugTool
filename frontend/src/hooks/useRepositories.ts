import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQueries } from '@tanstack/react-query';
import { getRepositories, getRepositoryStatistics } from '../services/repositories';
import type { Repository } from '../types/repositories';

const repositoryCacheOptions = {
  staleTime: 5 * 60_000,
  gcTime: 30 * 60_000,
  retry: false,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
} as const;

export const repositoryQueries = {
  catalog: () => infiniteQueryOptions({
    queryKey: ['repositories', 'catalog'],
    queryFn: ({ pageParam, signal }) => getRepositories(pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextToken ?? undefined,
    ...repositoryCacheOptions,
  }),
  statistics: (id: number) => queryOptions({
    queryKey: ['repositories', id, 'statistics'],
    queryFn: ({ signal }) => getRepositoryStatistics(id, signal),
    ...repositoryCacheOptions,
  }),
};

export function useRepositories() {
  return useInfiniteQuery(repositoryQueries.catalog());
}

export function useRepositoryStatistics(repositories: Repository[]) {
  return useQueries({ queries: repositories.map(({ id }) => repositoryQueries.statistics(id)) });
}
