import { useState } from 'react';
import { Alert, Button, Card, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { RefreshRounded, SearchRounded } from '@mui/icons-material';
import { PageHeader } from '../components/PageHeader';
import RepositorySelector from '../components/RepositorySelector';
import RepositoryGrid from '../components/RepositoryGrid';
import { useRepositories, useRepositoryStatistics } from '../hooks/useRepositories';
import { repositoryErrorMessage } from '../services/repositories';

export default function RepositoryDashboard({ title }: { title: string }) {
  const [search, setSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const catalog = useRepositories();
  const repositories = [...new Map(catalog.data?.pages.flatMap((page) => page.items).map((repository) => [repository.id, repository])).values()];
  const selected = repositories.filter((repository) => selectedIds.includes(repository.id));
  const visible = repositories.filter((repository) =>
    (!selectedIds.length || selectedIds.includes(repository.id)) &&
    repository.fullName.toLowerCase().includes(search.trim().toLowerCase()));
  const summaries = useRepositoryStatistics(visible);
  const refreshing = catalog.isFetching || summaries.some((summary) => summary.isFetching);

  function refresh() {
    void catalog.refetch();
    summaries.forEach((summary) => { void summary.refetch(); });
  }

  return <>
    <PageHeader title={title} description="Workflow health across your persisted repositories."
      action={<Button variant="outlined" startIcon={<RefreshRounded />} disabled={refreshing} onClick={refresh}>
        {refreshing ? 'Refreshing…' : 'Refresh'}
      </Button>} />
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ mb: 2.5 }}>
      <RepositorySelector repositories={repositories} selected={selected} disabled={catalog.isPending}
        onChange={(value) => setSelectedIds(value.map((repository) => repository.id))} />
      <TextField size="small" label="Search repositories" value={search} onChange={(event) => setSearch(event.target.value)}
        slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchRounded /></InputAdornment> } }}
        sx={{ width: { xs: '100%', md: 300 } }} />
    </Stack>
    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
      {repositories.length} repositories loaded. Select none to show all loaded repositories.
      {' '}Counts cover persisted workflow attempts, not all GitHub activity. Failed runs have analyzed failed or timed-out jobs.
    </Typography>
    {catalog.isError && <Alert severity="error" sx={{ mb: 2 }}
      action={<Button color="inherit" size="small" disabled={catalog.isFetching} onClick={() => { void (catalog.isFetchNextPageError ? catalog.fetchNextPage() : catalog.refetch()); }}>Retry</Button>}>
      {repositoryErrorMessage(catalog.error)}{catalog.data && ' Previously loaded repositories remain available.'}
    </Alert>}
    {!catalog.isPending && !catalog.isError && repositories.length === 0 && !catalog.hasNextPage
      ? <Card sx={{ p: 4, textAlign: 'center' }}>
          <Typography component="h2" variant="h6">No repositories analyzed yet</Typography>
          <Typography color="text.secondary" sx={{ mt: 1 }}>Analyze a workflow run or configure GitHub webhooks, then refresh to discover your repositories.</Typography>
        </Card>
      : (!catalog.isError || repositories.length > 0) && <RepositoryGrid repositories={visible} summaries={summaries}
          loading={catalog.isPending} onRetry={(index) => { void summaries[index].refetch(); }} />}
    {catalog.hasNextPage && <Stack sx={{ alignItems: 'center', mt: 3 }}>
      <Button variant="outlined" disabled={refreshing} onClick={() => { void catalog.fetchNextPage(); }}>
        {catalog.isFetchingNextPage ? 'Loading repositories…' : 'Load more repositories'}
      </Button>
    </Stack>}
  </>;
}
