// @vitest-environment jsdom
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import RepositoryDashboard from './RepositoryDashboard';
import { getRepositories, getRepositoryStatistics } from '../services/repositories';
import type { Repository, RepositoryStatistics } from '../types/repositories';

vi.mock('../services/repositories', async (importOriginal) => ({
  ...await importOriginal<typeof import('../services/repositories')>(),
  getRepositories: vi.fn(),
  getRepositoryStatistics: vi.fn(),
}));

const repositories: Repository[] = [
  { id: 1, name: 'api', fullName: 'octo/api', htmlUrl: null, privateRepository: true },
  { id: 2, name: 'web', fullName: 'octo/web', htmlUrl: null, privateRepository: false },
];
const statistics: RepositoryStatistics = {
  totalRuns: 42, completedAnalyses: 40, processingAnalyses: 1, failedAnalyses: 1,
  failureRuns: 5, unavailableJobAnalyses: 0,
  failuresByCategory: {
    JAVA_COMPILATION: 5, MAVEN_DEPENDENCY: 0, JUNIT_TEST: 0, POSTGRESQL_CONNECTION: 0,
    DOCKER_BUILD: 0, AUTHENTICATION: 0, GITHUB_ACTIONS_CONFIGURATION: 0, UNKNOWN: 0,
  },
  latestRun: {
    runId: 7, attempt: 1, workflowName: 'CI', branch: 'main', conclusion: 'failure',
    htmlUrl: null, createdAt: '2026-10-20T10:00:00Z', analyzedAt: '2026-10-20T10:01:00Z',
    analysisStatus: 'COMPLETED', analysisComplete: true, categories: ['JAVA_COMPILATION'],
  },
  lastAnalyzedAt: '2026-10-20T10:01:00Z',
};

let client: QueryClient;

function dashboard(title = 'Overview') {
  return <QueryClientProvider client={client}><RepositoryDashboard title={title} /></QueryClientProvider>;
}

beforeEach(() => {
  vi.resetAllMocks();
  client = new QueryClient();
  vi.mocked(getRepositories).mockResolvedValue({ items: repositories, nextToken: null });
  vi.mocked(getRepositoryStatistics).mockResolvedValue(statistics);
});

afterEach(() => {
  cleanup();
  client.clear();
});

describe('repository dashboard', () => {
  it('renders real summary fields and reuses cached data across dashboard routes', async () => {
    const view = render(dashboard());
    const card = await screen.findByRole('article', { name: 'octo/api' });
    await within(card).findByText('42');
    expect(within(card).getByText('5')).toBeDefined();
    expect(within(card).getByText('failure')).toBeDefined();
    expect(card.querySelector('time')?.getAttribute('dateTime')).toBe(statistics.lastAnalyzedAt);
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).toHaveBeenCalledTimes(2);
    view.unmount();
    render(dashboard('Repositories'));
    await screen.findByRole('heading', { name: 'Repositories', level: 1 });
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).toHaveBeenCalledTimes(2);
  });

  it('filters and selects locally without repeated requests, and refreshes once per visible repository', async () => {
    const user = userEvent.setup();
    render(dashboard());
    await screen.findAllByText('42');
    const search = screen.getByRole('textbox', { name: 'Search repositories' });
    await user.type(search, 'api');
    expect(screen.queryByRole('article', { name: 'octo/web' })).toBeNull();
    await user.clear(search);
    await user.click(screen.getByRole('combobox', { name: 'Select repositories' }));
    await user.click(await screen.findByRole('option', { name: 'octo/api' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('article', { name: 'octo/web' })).toBeNull();
    act(() => { window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('online')); });
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).toHaveBeenCalledTimes(2);
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(getRepositoryStatistics).toHaveBeenCalledTimes(3));
    expect(getRepositories).toHaveBeenCalledTimes(2);
    expect(vi.mocked(getRepositoryStatistics).mock.calls[2][0]).toBe(1);
  });

  it('shows a skeleton while repository discovery is pending', () => {
    vi.mocked(getRepositories).mockImplementation(() => new Promise(() => {}));
    render(dashboard());
    expect(screen.getAllByRole('status', { name: 'Loading repository' })).toHaveLength(3);
    expect(getRepositoryStatistics).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Refreshing…' }).hasAttribute('disabled')).toBe(true);
  });

  it('does not show zero counts while statistics are loading', async () => {
    vi.mocked(getRepositoryStatistics).mockImplementation(() => new Promise(() => {}));
    render(dashboard());
    await screen.findByRole('status', { name: 'Loading statistics for octo/api' });
    expect(screen.queryByText('0')).toBeNull();
  });

  it('handles empty catalogs without requesting statistics', async () => {
    vi.mocked(getRepositories).mockResolvedValue({ items: [], nextToken: null });
    render(dashboard());
    await screen.findByText('No repositories analyzed yet');
    expect(getRepositoryStatistics).not.toHaveBeenCalled();
  });

  it('shows no matches for a local search without making API requests', async () => {
    const user = userEvent.setup();
    render(dashboard());
    await screen.findAllByText('42');
    await user.type(screen.getByRole('textbox', { name: 'Search repositories' }), 'absent');
    await screen.findByText('No matching repositories');
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).toHaveBeenCalledTimes(2);
  });

  it('surfaces discovery errors and retries only on user request', async () => {
    vi.mocked(getRepositories).mockRejectedValueOnce(new Error('Database unavailable'));
    render(dashboard());
    await screen.findByText('Database unavailable');
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findAllByText('42');
    expect(getRepositories).toHaveBeenCalledTimes(2);
  });

  it('keeps successful cards when one repository fails and supports per-card retry', async () => {
    vi.mocked(getRepositoryStatistics).mockImplementation(async (id) => {
      if (id === 1) throw new Error('Statistics unavailable');
      return statistics;
    });
    render(dashboard());
    const failed = await screen.findByRole('article', { name: 'octo/api' });
    await within(failed).findByText('Statistics unavailable');
    const successful = screen.getByRole('article', { name: 'octo/web' });
    await within(successful).findByText('42');
    vi.mocked(getRepositoryStatistics).mockResolvedValue(statistics);
    await userEvent.click(within(failed).getByRole('button', { name: 'Retry' }));
    await within(failed).findByText('42');
    expect(getRepositoryStatistics).toHaveBeenCalledTimes(3);
    expect(getRepositories).toHaveBeenCalledTimes(1);
  });

  it('retains existing statistics and reports errors when refresh fails', async () => {
    render(dashboard());
    await screen.findAllByText('42');
    vi.mocked(getRepositoryStatistics).mockRejectedValue(new Error('Refresh failed'));
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findAllByText('Refresh failed Showing previously loaded data.');
    expect(screen.getAllByText('42')).toHaveLength(2);
  });

  it('loads continuation pages explicitly even if a page is empty', async () => {
    vi.mocked(getRepositories).mockResolvedValueOnce({ items: [], nextToken: 'opaque/token' })
      .mockResolvedValueOnce({ items: repositories, nextToken: null });
    render(dashboard());
    await screen.findByRole('button', { name: 'Load more repositories' });
    expect(getRepositories).toHaveBeenCalledTimes(1);
    expect(getRepositoryStatistics).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Load more repositories' }));
    await screen.findAllByText('42');
    expect(vi.mocked(getRepositories).mock.calls[1][0]).toBe('opaque/token');
    expect(screen.queryByRole('button', { name: 'Load more repositories' })).toBeNull();
  });

  it('distinguishes no runs and no completed analysis from workflow failure', async () => {
    vi.mocked(getRepositoryStatistics).mockResolvedValue({
      ...statistics, totalRuns: 0, failureRuns: 0, latestRun: null, lastAnalyzedAt: null,
    });
    render(dashboard());
    await screen.findAllByText('No workflow runs');
    expect(screen.getAllByText('Not analyzed yet')).toHaveLength(2);
  });

  it('does not confuse an analysis failure with a workflow conclusion', async () => {
    vi.mocked(getRepositoryStatistics).mockResolvedValue({
      ...statistics,
      latestRun: { ...statistics.latestRun!, conclusion: null, analysisStatus: 'FAILED' },
      lastAnalyzedAt: null,
    });
    render(dashboard());
    await screen.findAllByText('In progress / pending');
    expect(screen.getAllByText('CI · Analysis failed')).toHaveLength(2);
  });
});
