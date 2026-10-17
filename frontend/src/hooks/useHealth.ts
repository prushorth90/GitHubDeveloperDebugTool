import { useQuery } from '@tanstack/react-query';
import { getHealth } from '../services/api';

export function useHealth() {
  return useQuery({
    queryKey: ['health'],
    queryFn: ({ signal }) => getHealth(signal),
    retry: false,
    refetchInterval: 30_000,
    staleTime: 10_000,
  });
}
