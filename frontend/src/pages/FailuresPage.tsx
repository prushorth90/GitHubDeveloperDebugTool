import { useState } from 'react';
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, FormControl, InputLabel, Link, MenuItem, Select, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import { skipToken, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/DashboardCards';
import { useRepositories } from '../hooks/useRepositories';
import { getRepositoryFailures, getWorkflowFailure, repositoryErrorMessage } from '../services/repositories';

const cacheOptions = {
  staleTime: 5 * 60_000,
  gcTime: 30 * 60_000,
  retry: false,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
} as const;

export default function FailuresPage() {
  const [selectedId, setSelectedId] = useState<number | ''>('');
  const [selectedRun, setSelectedRun] = useState<{ repositoryId: number; runId: number; attempt: number } | null>(null);
  const catalog = useRepositories();
  const repositories = catalog.data?.pages.flatMap((page) => page.items) ?? [];
  const repositoryId = selectedId || repositories[0]?.id;
  const failures = useInfiniteQuery({
    queryKey: ['repositories', repositoryId, 'failures'],
    queryFn: ({ pageParam, signal }) => {
      if (repositoryId === undefined) throw new Error('Select a repository to load failures.');
      return getRepositoryFailures(repositoryId, pageParam, signal);
    },
    enabled: repositoryId !== undefined,
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextToken ?? undefined,
    ...cacheOptions,
  });
  const selection = selectedRun?.repositoryId === repositoryId ? selectedRun : null;
  const detail = useQuery({
    queryKey: ['repositories', repositoryId, 'failures', selection?.runId, selection?.attempt],
    queryFn: selection === null ? skipToken : ({ signal }) =>
      getWorkflowFailure(selection.repositoryId, selection.runId, selection.attempt, signal),
    ...cacheOptions,
  });
  const runs = failures.data?.pages.flatMap((page) => page.items) ?? [];

  return <>
    <PageHeader title="Workflow Failures" description="Investigate failures from persisted GitHub workflow attempts."
      action={<Button disabled={catalog.isFetching || failures.isFetching || detail.isFetching} onClick={() => {
        void catalog.refetch();
        if (repositoryId !== undefined) void failures.refetch();
        if (selection) void detail.refetch();
      }}>Refresh</Button>} />
    <Stack direction="row" spacing={2} sx={{ mb: 3 }}>
      <FormControl size="small" sx={{ minWidth: 260 }}>
        <InputLabel id="failures-repository-label">Repository</InputLabel>
        <Select labelId="failures-repository-label" label="Repository" value={repositoryId ?? ''}
          disabled={!repositories.length} onChange={(event) => {
            setSelectedId(Number(event.target.value));
            setSelectedRun(null);
          }}>
          {repositories.map((repo) => <MenuItem key={repo.id} value={repo.id}>{repo.fullName}</MenuItem>)}
        </Select>
      </FormControl>
      {catalog.hasNextPage && <Button disabled={catalog.isFetching} onClick={() => void catalog.fetchNextPage()}>
        Load more repositories
      </Button>}
    </Stack>
    {catalog.isPending && <Typography role="status">Loading repositories...</Typography>}
    {catalog.isError && <Alert severity="error" action={<Button onClick={() => void catalog.refetch()}>Retry repositories</Button>} sx={{ mb: 2 }}>
      {repositoryErrorMessage(catalog.error)}
    </Alert>}
    {catalog.isSuccess && !repositories.length && <Alert severity="info">
      No repositories recorded yet. Configure completed workflow webhooks to start collecting analyses.
    </Alert>}
    {repositoryId !== undefined && <>
      {failures.isFetching && <Typography role="status">Loading failures...</Typography>}
      {failures.isError && <Alert severity="error" action={<Button onClick={() =>
        void (failures.isFetchNextPageError ? failures.fetchNextPage() : failures.refetch())}>Retry failures</Button>} sx={{ mb: 2 }}>
        {repositoryErrorMessage(failures.error)}{failures.data ? ' Showing previously loaded data.' : ''}
      </Alert>}
      <SectionCard title="Failed workflow runs" subtitle="Persisted attempts with failed or timed-out jobs">
        <TableContainer>
          <Table aria-label="Workflow failures" size="small">
            <TableHead><TableRow>
              <TableCell>Workflow / Run</TableCell><TableCell>Branch</TableCell>
              <TableCell>Conclusion</TableCell><TableCell>Categories</TableCell><TableCell>Analysis</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {runs.map((run) => <TableRow key={`${run.runId}-${run.attempt}`}>
                <TableCell>
                  <Typography>{run.workflowName ?? 'Unnamed workflow'}</Typography>
                  <Button size="small" onClick={() => setSelectedRun({ repositoryId, runId: run.runId, attempt: run.attempt })}>
                    Run {run.runId} / attempt {run.attempt}
                  </Button>
                  {run.htmlUrl && <Link href={run.htmlUrl} target="_blank" rel="noopener noreferrer">GitHub logs</Link>}
                </TableCell>
                <TableCell>{run.branch ?? 'Unknown'}</TableCell>
                <TableCell>{run.conclusion ?? 'Unknown'}</TableCell>
                <TableCell>{run.categories.join(', ') || 'Unclassified'}</TableCell>
                <TableCell>{run.analysisStatus}{!run.analysisComplete && ' (incomplete)'}</TableCell>
              </TableRow>)}
              {failures.isSuccess && !runs.length && <TableRow>
                <TableCell colSpan={5}>No recorded failures for this repository.</TableCell>
              </TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      </SectionCard>
      {failures.hasNextPage && <Button disabled={failures.isFetching} onClick={() => void failures.fetchNextPage()} sx={{ mt: 2 }}>
        Load more failures
      </Button>}
    </>}
    {selection && <Box sx={{ mt: 3 }}>
      <SectionCard title={`Failure analysis: run ${selection.runId}, attempt ${selection.attempt}`} subtitle="Analysis of sanitized GitHub Actions job logs">
        <Box sx={{ p: 2 }}>
          {detail.isFetching && <Typography role="status">Loading failure analysis...</Typography>}
          {detail.isError && <Alert severity="error" action={<Button onClick={() => void detail.refetch()}>Retry analysis</Button>}>
            {repositoryErrorMessage(detail.error)}{detail.data ? ' Showing previously loaded data.' : ''}
          </Alert>}
          {detail.data?.failedJobs.map((job) => <Box key={job.jobId} sx={{ mb: 2 }}>
            <Typography component="h3" variant="h6">{job.name}</Typography>
            {job.analysis ? <>
              <Stack direction="row" spacing={1} sx={{ my: 1, flexWrap: 'wrap' }} useFlexGap>
                <Chip label={job.analysis.category} />
                <Chip label={`Classification confidence: ${job.analysis.confidence}`} variant="outlined" />
                <Chip label={`Source: ${job.analysis.analysisSource ?? 'LEGACY_JAVA'}`} variant="outlined" />
              </Stack>
              {job.analysis.modelIdentifier && <Typography variant="body2">Model: {job.analysis.modelIdentifier}</Typography>}
              {job.analysis.fallbackReason && <Alert severity="warning" sx={{ my: 1 }}>
                Bedrock explanation unavailable ({job.analysis.fallbackReason}). Showing Java analysis.
              </Alert>}
              {(!job.analysis.analysisSource || job.analysis.analysisSource === 'LEGACY_JAVA') && <Alert severity="info">
                Legacy analysis: confidence and evidence have not been reassessed with the current analyzer.
              </Alert>}
              <Typography variant="subtitle2">Observed log message (not proof of root cause)</Typography>
              <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{job.analysis.errorMessage}</Typography>
              <Typography sx={{ mt: 1 }}>Root-cause hypothesis (unverified): {job.analysis.suspectedCause}</Typography>
              <Typography>Suggested investigation: {job.analysis.suggestedInvestigation}</Typography>
              {job.analysis.diagnosticFindings?.map((finding, index) => <Accordion key={index} disableGutters sx={{ mt: 1 }}>
                <AccordionSummary>
                  <Stack spacing={1}>
                    <Chip size="small" color={finding.kind === 'OBSERVATION' ? 'info' : 'warning'}
                      label={`${finding.kind === 'OBSERVATION' ? 'Observation' : 'Hypothesis (unverified)'} / ${finding.confidence}`} />
                    <Typography sx={{ overflowWrap: 'anywhere' }}>{finding.description}</Typography>
                  </Stack>
                </AccordionSummary>
                <AccordionDetails>
                  {finding.kind === 'OBSERVATION' && <Typography variant="body2">
                    Observation confidence concerns log text, not whether its claims are true or establish a root cause.
                  </Typography>}
                  <Typography variant="subtitle2">Supporting sanitized job log references</Typography>
                  <Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {finding.evidence.map((line) => `Line ${line.lineNumber}: ${line.text}`).join('\n')
                      || 'No direct log evidence; investigation required.'}
                  </Box>
                </AccordionDetails>
              </Accordion>)}
              <Typography variant="subtitle2" sx={{ mt: 2 }}>Classification evidence (sanitized job log lines)</Typography>
              <Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {job.analysis.evidence.map((line) => `${line.lineNumber}: ${line.text}`).join('\n')}
              </Box>
            </> : <Alert severity="warning">{job.logMessage ?? `Job logs ${job.logStatus.toLowerCase()}; no analysis available.`}</Alert>}
          </Box>)}
        </Box>
      </SectionCard>
    </Box>}
  </>;
}
