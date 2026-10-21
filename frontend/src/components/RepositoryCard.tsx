import { Alert, Box, Button, Card, Chip, Grid, Skeleton, Stack, Typography } from '@mui/material';
import { FolderOpenRounded } from '@mui/icons-material';
import { repositoryErrorMessage } from '../services/repositories';
import type { Repository, RepositoryStatistics, WorkflowRun } from '../types/repositories';

export interface RepositorySummaryState {
  data: RepositoryStatistics | undefined;
  error: Error | null;
  isPending: boolean;
  isFetching: boolean;
}

function WorkflowStatus({ run }: { run: WorkflowRun | null }) {
  if (!run) return <Chip label="No workflow runs" variant="outlined" />;
  const conclusion = run.conclusion;
  const color = conclusion === 'success' ? 'success'
    : conclusion === 'failure' || conclusion === 'timed_out' ? 'error' : 'default';
  return <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
    <Chip label={conclusion ? conclusion.replaceAll('_', ' ') : 'In progress / pending'} color={color} />
    <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
      {run.workflowName ?? 'Unnamed workflow'}
      {run.analysisStatus === 'FAILED' ? ' · Analysis failed' : run.analysisStatus === 'PROCESSING' ? ' · Analysis processing' : ''}
    </Typography>
  </Stack>;
}

export function RepositoryCardSkeleton() {
  return <Card role="status" aria-label="Loading repository" sx={{ p: 3 }}>
    <Skeleton width="70%" height={32} />
    <Skeleton width="40%" />
    <Skeleton height={90} />
    <Skeleton width="60%" />
  </Card>;
}

export default function RepositoryCard({ repository, summary, onRetry }: {
  repository: Repository;
  summary: RepositorySummaryState;
  onRetry: () => void;
}) {
  const { data, error, isPending, isFetching } = summary;
  return <Card component="article" aria-label={repository.fullName} aria-busy={isFetching} sx={{ p: 3, height: '100%' }}>
    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
      <FolderOpenRounded color="primary" />
      <Chip label={repository.privateRepository ? 'Private' : 'Public'} variant="outlined" />
    </Stack>
    <Typography component="h2" variant="h6" sx={{ overflowWrap: 'anywhere' }}>{repository.fullName}</Typography>
    {error && <Alert severity="error" sx={{ mt: 2 }} action={<Button color="inherit" size="small" disabled={isFetching} onClick={onRetry}>Retry</Button>}>
      {repositoryErrorMessage(error)}{data && ' Showing previously loaded data.'}
    </Alert>}
    {isPending && <Box role="status" aria-label={`Loading statistics for ${repository.fullName}`}><Skeleton height={80} /><Skeleton width="60%" /></Box>}
    {data && <>
      <Grid container spacing={2} sx={{ my: 2 }}>
        {[['Total workflow runs', data.totalRuns], ['Failed runs', data.failureRuns]].map(([label, value]) => <Grid key={label} size={6}>
          <Typography variant="body2" color="text.secondary">{label}</Typography>
          <Typography sx={{ fontSize: 26, fontWeight: 700 }}>{typeof value === 'number' ? value.toLocaleString() : value}</Typography>
        </Grid>)}
      </Grid>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>Latest workflow status</Typography>
      <WorkflowStatus run={data.latestRun} />
      <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>Last analyzed</Typography>
      {data.lastAnalyzedAt
        ? <Typography component="time" dateTime={data.lastAnalyzedAt} variant="body2">{new Date(data.lastAnalyzedAt).toLocaleString()}</Typography>
        : <Typography variant="body2">Not analyzed yet</Typography>}
    </>}
  </Card>;
}
