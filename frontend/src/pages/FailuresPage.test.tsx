// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FailuresPage from './FailuresPage';
import { getRepositories, getRepositoryFailures, getWorkflowFailure } from '../services/repositories';
import type { WorkflowFailure, WorkflowRun } from '../types/repositories';

vi.mock('../services/repositories', async (importOriginal) => ({
  ...await importOriginal<typeof import('../services/repositories')>(),
  getRepositories: vi.fn(),
  getRepositoryFailures: vi.fn(),
  getWorkflowFailure: vi.fn(),
}));

const run: WorkflowRun = {
  runId: 99, attempt: 2, workflowName: 'Build', branch: 'main', conclusion: 'failure',
  htmlUrl: 'https://github.com/octo/api/actions/runs/99', createdAt: '2026-10-20T00:00:00Z',
  analyzedAt: '2026-10-20T00:01:00Z', analysisStatus: 'COMPLETED', analysisComplete: true,
  categories: ['JAVA_COMPILATION'],
};
const failure: WorkflowFailure = {
  run, failedJobs: [{
    jobId: 7, name: 'compile', logStatus: 'AVAILABLE', logMessage: null,
    analysis: { category: 'JAVA_COMPILATION', errorMessage: 'Compilation failed',
      suspectedCause: 'Invalid Java source', suggestedInvestigation: 'Check compiler output',
      confidence: 'HIGH', evidence: [{ lineNumber: 3, text: 'error: missing symbol' }] },
  }],
};
let client: QueryClient;
function dashboard() {
  return render(<QueryClientProvider client={client}><FailuresPage /></QueryClientProvider>);
}
beforeEach(() => {
  vi.resetAllMocks();
  client = new QueryClient();
  vi.mocked(getRepositories).mockResolvedValue({ items: [
    { id: 1, name: 'api', fullName: 'octo/api', htmlUrl: null, privateRepository: true },
    { id: 2, name: 'web', fullName: 'octo/web', htmlUrl: null, privateRepository: false },
  ], nextToken: null });
  vi.mocked(getRepositoryFailures).mockResolvedValue({ items: [run], nextToken: null });
  vi.mocked(getWorkflowFailure).mockResolvedValue(failure);
});
afterEach(() => { cleanup(); client.clear(); });

describe('persisted workflow failures', () => {
  it('distinguishes observations, hypotheses, provenance, and supporting log references', async () => {
    vi.mocked(getWorkflowFailure).mockResolvedValue({
      ...failure, failedJobs: [{ ...failure.failedJobs[0], analysis: {
        ...failure.failedJobs[0].analysis!, confidence: 'LOW', analysisSource: 'BEDROCK', modelIdentifier: 'test-model',
        diagnosticFindings: [
          { kind: 'OBSERVATION', description: 'echo "Error: Could not connect to PostgreSQL"', confidence: 'HIGH',
            evidence: [{ lineNumber: 5, text: 'echo "Error: Could not connect to PostgreSQL"' }] },
          { kind: 'HYPOTHESIS', description: 'The failure may be intentional.', confidence: 'LOW', evidence: [] },
        ],
      } }],
    });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Run 99 / attempt 2' }));
    await screen.findByText('Source: BEDROCK');
    expect(screen.getByText('Model: test-model')).toBeDefined();
    expect(screen.getByText('Classification confidence: LOW')).toBeDefined();
    expect(screen.getByText('Hypothesis (unverified) / LOW')).toBeDefined();
    await userEvent.click(screen.getByRole('button', { name: /Observation \/ HIGH/ }));
    expect(screen.getByText(/Observation confidence concerns log text/)).toBeDefined();
    expect(screen.getByText('Line 5: echo "Error: Could not connect to PostgreSQL"')).toBeDefined();
  });

  it('renders model hypotheses and resolved log text as text, never HTML', async () => {
    const text = '<img src=x onerror="alert(1)">';
    vi.mocked(getWorkflowFailure).mockResolvedValue({
      ...failure, failedJobs: [{ ...failure.failedJobs[0], analysis: {
        ...failure.failedJobs[0].analysis!, analysisSource: 'BEDROCK',
        diagnosticFindings: [
          { kind: 'OBSERVATION', description: text, confidence: 'HIGH',
            evidence: [{ lineNumber: 3, text }] },
          { kind: 'HYPOTHESIS', description: `Unverified: ${text}`, confidence: 'LOW',
            evidence: [{ lineNumber: 3, text }] },
        ],
      } }],
    });
    const view = dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Run 99 / attempt 2' }));
    await screen.findByText(text);
    expect(screen.getByText(`Unverified: ${text}`)).toBeDefined();
    expect(screen.getByText('Hypothesis (unverified) / LOW')).toBeDefined();
    await userEvent.click(screen.getByRole('button', { name: /Observation \/ HIGH/ }));
    expect(screen.getAllByText(`Line 3: ${text}`)).toHaveLength(2);
    expect(view.container.querySelector('img')).toBeNull();
  });

  it('identifies legacy analyses and explicit Java fallback reasons', async () => {
    vi.mocked(getWorkflowFailure).mockResolvedValue({
      ...failure, failedJobs: [{ ...failure.failedJobs[0], analysis: {
        ...failure.failedJobs[0].analysis!, analysisSource: 'JAVA', fallbackReason: 'BEDROCK_UNAVAILABLE',
      } }],
    });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Run 99 / attempt 2' }));
    await screen.findByText(/Showing Java analysis/);
    expect(screen.getByText('Source: JAVA')).toBeDefined();
  });

  it('loads failures and requests the selected attempt only when opened', async () => {
    dashboard();
    const button = await screen.findByRole('button', { name: 'Run 99 / attempt 2' });
    expect(getRepositoryFailures).toHaveBeenCalledExactlyOnceWith(1, null, expect.any(AbortSignal));
    expect(getWorkflowFailure).not.toHaveBeenCalled();
    expect(screen.queryByText(/sample failures/i)).toBeNull();
    expect(screen.getByRole('link', { name: 'GitHub logs' }).getAttribute('href')).toBe(run.htmlUrl);
    await userEvent.click(button);
    await screen.findByText('Compilation failed');
    expect(screen.getByText('3: error: missing symbol')).toBeDefined();
    expect(getWorkflowFailure).toHaveBeenCalledExactlyOnceWith(1, 99, 2, expect.any(AbortSignal));
  });

  it('paginates with opaque cursors and resets detail on repository changes', async () => {
    vi.mocked(getRepositoryFailures).mockResolvedValueOnce({ items: [run], nextToken: 'next' })
      .mockResolvedValueOnce({ items: [{ ...run, runId: 100 }], nextToken: null })
      .mockResolvedValue({ items: [], nextToken: null });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Load more failures' }));
    await screen.findByRole('button', { name: 'Run 100 / attempt 2' });
    expect(getRepositoryFailures).toHaveBeenLastCalledWith(1, 'next', expect.any(AbortSignal));
    await userEvent.click(screen.getByRole('button', { name: 'Run 99 / attempt 2' }));
    await screen.findByText('Compilation failed');
    await userEvent.click(screen.getByRole('combobox', { name: 'Repository' }));
    await userEvent.click(screen.getByRole('option', { name: 'octo/web' }));
    await screen.findByText('No recorded failures for this repository.');
    expect(getRepositoryFailures).toHaveBeenLastCalledWith(2, null, expect.any(AbortSignal));
    expect(screen.queryByText('Compilation failed')).toBeNull();
  });

  it('shows loading and empty catalog states without inventing records', async () => {
    vi.mocked(getRepositories).mockResolvedValue({ items: [], nextToken: null });
    dashboard();
    await screen.findByText(/No repositories recorded yet/);
    expect(getRepositoryFailures).not.toHaveBeenCalled();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('surfaces list errors, retries and preserves records when refresh fails', async () => {
    vi.mocked(getRepositoryFailures).mockRejectedValueOnce(new Error('Queue analysis unavailable'));
    dashboard();
    await screen.findByText('Queue analysis unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Retry failures' }));
    await screen.findByRole('button', { name: 'Run 99 / attempt 2' });
    vi.mocked(getRepositoryFailures).mockRejectedValue(new Error('Refresh failed'));
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await screen.findByText('Refresh failed Showing previously loaded data.');
    expect(screen.getByRole('button', { name: 'Run 99 / attempt 2' })).toBeDefined();
  });

  it('surfaces detail errors and unavailable logs without fabricating an analysis', async () => {
    vi.mocked(getWorkflowFailure).mockRejectedValueOnce(new Error('Detail unavailable'))
      .mockResolvedValue({ ...failure, failedJobs: [{ ...failure.failedJobs[0], analysis: null,
        logStatus: 'UNAVAILABLE', logMessage: 'GitHub logs expired' }] });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Run 99 / attempt 2' }));
    await screen.findByText('Detail unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Retry analysis' }));
    await screen.findByText('GitHub logs expired');
    expect(screen.queryByText('Compilation failed')).toBeNull();
  });

  it('supports later repository pages even when the first page is empty', async () => {
    vi.mocked(getRepositories).mockResolvedValueOnce({ items: [], nextToken: 'repositories-next' });
    dashboard();
    await userEvent.click(await screen.findByRole('button', { name: 'Load more repositories' }));
    await screen.findByRole('button', { name: 'Run 99 / attempt 2' });
    await waitFor(() => expect(getRepositories).toHaveBeenLastCalledWith('repositories-next', expect.any(AbortSignal)));
  });
});
