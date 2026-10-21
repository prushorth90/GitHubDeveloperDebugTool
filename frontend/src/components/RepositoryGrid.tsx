import { Card, Grid, Typography } from '@mui/material';
import RepositoryCard, { RepositoryCardSkeleton } from './RepositoryCard';
import type { RepositorySummaryState } from './RepositoryCard';
import type { Repository } from '../types/repositories';

export default function RepositoryGrid({ repositories, summaries, loading, onRetry }: {
  repositories: Repository[];
  summaries: RepositorySummaryState[];
  loading: boolean;
  onRetry: (index: number) => void;
}) {
  if (loading) return <Grid container spacing={2.5}>
    {[0, 1, 2].map((key) => <Grid key={key} size={{ xs: 12, sm: 6, xl: 4 }}><RepositoryCardSkeleton /></Grid>)}
  </Grid>;
  if (!repositories.length) return <Card sx={{ p: 4, textAlign: 'center' }}>
    <Typography component="h2" variant="h6">No matching repositories</Typography>
    <Typography color="text.secondary" sx={{ mt: 1 }}>Clear the search or change your repository selection.</Typography>
  </Card>;
  return <Grid container spacing={2.5}>
    {repositories.map((repository, index) => <Grid key={repository.id} size={{ xs: 12, sm: 6, xl: 4 }}>
      <RepositoryCard repository={repository} summary={summaries[index]} onRetry={() => onRetry(index)} />
    </Grid>)}
  </Grid>;
}
