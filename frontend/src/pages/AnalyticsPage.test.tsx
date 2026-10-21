// @vitest-environment jsdom
import { cloneElement, type ReactElement } from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AnalyticsPage from './AnalyticsPage';
import { getRepositories, getWorkflowAnalytics } from '../services/repositories';
import type { WorkflowAnalytics } from '../types/repositories';

vi.mock('../services/repositories', async (importOriginal) => ({
  ...await importOriginal<typeof import('../services/repositories')>(),
  getRepositories: vi.fn(),
  getWorkflowAnalytics: vi.fn(),
}));
vi.mock('recharts', async (importOriginal) => ({
  ...await importOriginal<typeof import('recharts')>(),
  ResponsiveContainer: ({ children }: { children: ReactElement<{ width: number; height: number }> }) =>
    cloneElement(children, { width: 640, height: 320 }),
}));

const summary: WorkflowAnalytics = {
  from: '2026-10-14T00:00:00Z', until: '2026-10-20T12:00:00Z',
  totalRuns: 10, failedRuns: 2, recurringFailures: 1, successfulAnalyses: 2,
  dailyRates: [
    { date: '2026-10-14', totalRuns: 10, failedRuns: 2, failureRate: 20 },
    { date: '2026-10-15', totalRuns: 0, failedRuns: 0, failureRate: null },
  ],
  failuresByCategory: { JAVA_COMPILATION: 2, MAVEN_DEPENDENCY: 0, JUNIT_TEST: 0,
    POSTGRESQL_CONNECTION: 0, DOCKER_BUILD: 0, AUTHENTICATION: 0,
    GITHUB_ACTIONS_CONFIGURATION: 0, UNKNOWN: 0 },
};
let client: QueryClient;
function dashboard() {
  return render(<QueryClientProvider client={client}><AnalyticsPage /></QueryClientProvider>);
}
beforeEach(() => {
  vi.resetAllMocks();
  client = new QueryClient();
  vi.mocked(getRepositories).mockResolvedValue({ items: [
    { id: 1, name: 'api', fullName: 'octo/api', htmlUrl: null, privateRepository: true },
    { id: 2, name: 'web', fullName: 'octo/web', htmlUrl: null, privateRepository: false },
  ], nextToken: null });
  vi.mocked(getWorkflowAnalytics).mockResolvedValue(summary);
});
afterEach(() => { cleanup(); client.clear(); });

describe('analytics dashboard', () => {
  it('renders aggregate cards, charts and accessible tables without loading raw runs', async () => {
    dashboard();
    await screen.findByRole('region', { name: 'Daily workflow failure rate chart' });
    expect(screen.getByRole('region', { name: 'Failures by category chart' })).toBeDefined();
    for (const label of ['Total runs', 'Failed runs', 'Recurring failures', 'Successful analyses']) {
      expect(screen.getByRole('heading', { name: label })).toBeDefined();
      expect(screen.getByRole('button', { name: `About ${label.toLowerCase()}` })).toBeDefined();
    }
    const daily = screen.getByRole('table', { name: 'Daily workflow failure rates', hidden: true });
    expect(within(daily).getByText('20.0%')).toBeDefined();
    expect(within(daily).getByText('No runs')).toBeDefined();
    expect(getWorkflowAnalytics).toHaveBeenCalledExactlyOnceWith(1, 7, expect.any(AbortSignal));
  });

  it('requests repository and 7/30/90-day aggregates, caching previous selections', async () => {
    const user = userEvent.setup();
    dashboard();
    await screen.findByText('10', { selector: 'p' });
    for (const days of [30, 90, 7]) {
      await user.click(screen.getByRole('combobox', { name: 'Time range' }));
      await user.click(screen.getByRole('option', { name: `Last ${days} days` }));
      await screen.findByRole('region', { name: 'Daily workflow failure rate chart' });
    }
    expect(getWorkflowAnalytics).toHaveBeenCalledTimes(3);
    await user.click(screen.getByRole('combobox', { name: 'Repository' }));
    await user.click(screen.getByRole('option', { name: 'octo/web' }));
    await waitFor(() => expect(getWorkflowAnalytics).toHaveBeenLastCalledWith(2, 7, expect.any(AbortSignal)));
    expect(getRepositories).toHaveBeenCalledTimes(1);
  });

  it('shows loading rather than fake zero metrics', async () => {
    vi.mocked(getWorkflowAnalytics).mockImplementation(() => new Promise(() => {}));
    dashboard();
    await screen.findByText('Loading analytics...');
    expect(screen.queryByText('0')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Daily workflow failure rate chart' })).toBeNull();
  });

  it('shows an empty catalog without requesting analytics', async () => {
    vi.mocked(getRepositories).mockResolvedValue({ items: [], nextToken: null });
    dashboard();
    await screen.findByText(/No repositories recorded yet/);
    expect(getWorkflowAnalytics).not.toHaveBeenCalled();
  });

  it('distinguishes no runs, successful-only days and unclassified failures', async () => {
    vi.mocked(getWorkflowAnalytics).mockResolvedValue({
      ...summary, totalRuns: 0, failedRuns: 0, failuresByCategory: { ...summary.failuresByCategory, JAVA_COMPILATION: 0 },
    });
    dashboard();
    await screen.findByText(/No workflow runs in this time range/);
    expect(screen.getByText('No failed workflows in this time range.')).toBeDefined();
    vi.mocked(getWorkflowAnalytics).mockResolvedValue({
      ...summary, failuresByCategory: { ...summary.failuresByCategory, JAVA_COMPILATION: 0 },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText(/No categorized failures yet/);
    expect(screen.getByRole('region', { name: 'Daily workflow failure rate chart' })).toBeDefined();
  });

  it('surfaces errors, retries explicitly and retains aggregates on refresh errors', async () => {
    vi.mocked(getWorkflowAnalytics).mockRejectedValueOnce(new Error('Analytics unavailable'));
    dashboard();
    await screen.findByText('Analytics unavailable');
    expect(screen.queryByText('0')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Retry analytics' }));
    await screen.findByText('10', { selector: 'p' });
    vi.mocked(getWorkflowAnalytics).mockRejectedValue(new Error('Refresh failed'));
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText('Refresh failed Showing previously loaded data.');
    expect(screen.getByText('10', { selector: 'p' })).toBeDefined();
  });

  it('allows loading later catalog pages, including an empty initial page with a cursor', async () => {
    vi.mocked(getRepositories).mockResolvedValueOnce({ items: [], nextToken: 'next' });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Load more repositories' }));
    await screen.findByText('10', { selector: 'p' });
    expect(getRepositories).toHaveBeenLastCalledWith('next', expect.any(AbortSignal));
  });
});
