import { useState } from 'react';
import {
  Alert, Box, Button, Card, FormControl, Grid, IconButton, InputLabel, MenuItem,
  Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow,
  Tooltip as MuiTooltip, Typography,
} from '@mui/material';
import { InfoOutlined, RefreshRounded } from '@mui/icons-material';
import { skipToken, useQuery } from '@tanstack/react-query';
import {
  Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from 'recharts';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/DashboardCards';
import { useRepositories } from '../hooks/useRepositories';
import { getWorkflowAnalytics, repositoryErrorMessage } from '../services/repositories';

const categoryNames: Record<string, string> = {
  JAVA_COMPILATION: 'Java compilation',
  MAVEN_DEPENDENCY: 'Maven dependency',
  JUNIT_TEST: 'JUnit test',
  POSTGRESQL_CONNECTION: 'PostgreSQL connection',
  DOCKER_BUILD: 'Docker build',
  AUTHENTICATION: 'Authentication',
  GITHUB_ACTIONS_CONFIGURATION: 'Actions configuration',
  UNKNOWN: 'Unknown',
};

function ChartEmpty({ children }: { children: string }) {
  return <Box role="status" sx={{ p: 4, minHeight: 280, display: 'grid', placeItems: 'center', textAlign: 'center' }}>
    <Typography color="text.secondary">{children}</Typography>
  </Box>;
}

export default function AnalyticsPage() {
  const [days, setDays] = useState(7);
  const [selectedId, setSelectedId] = useState<number | ''>('');
  const catalog = useRepositories();
  const repositories = catalog.data?.pages.flatMap((page) => page.items) ?? [];
  const repositoryId = selectedId || repositories[0]?.id;
  const analytics = useQuery({
    queryKey: ['repositories', repositoryId, 'analytics', days],
    queryFn: repositoryId === undefined ? skipToken : ({ signal }) => getWorkflowAnalytics(repositoryId, days, signal),
    staleTime: 5 * 60_000,
    gcTime: 30 * 60_000,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const data = analytics.data;
  const categories = data ? Object.entries(data.failuresByCategory)
    .filter(([, count]) => count > 0)
    .map(([category, count]) => ({ category: categoryNames[category] ?? category, count })) : [];
  const metrics = [
    { label: 'Total runs', value: data?.totalRuns, help: 'All persisted completed workflow attempts, including success, failure, cancellation and other conclusions.' },
    { label: 'Failed runs', value: data?.failedRuns, help: 'Workflow attempts concluded as failure, timed out or startup failure, even if analysis is unavailable.' },
    { label: 'Recurring failures', value: data?.recurringFailures, help: 'Failed attempts sharing at least one analyzed fingerprint with an earlier attempt in this repository and time range. Each repeat counts once.' },
    { label: 'Successful analyses', value: data?.successfulAnalyses, help: 'Failed workflow attempts with at least one failed job and an available analysis for every failed job. Metadata-only successful workflows are excluded.' },
  ];
  return <>
    <PageHeader title="Analytics" description="Understand historical workflow failures and recurring patterns."
      action={<Button startIcon={<RefreshRounded />} disabled={catalog.isFetching || analytics.isFetching}
        onClick={() => { void catalog.refetch(); if (repositoryId) void analytics.refetch(); }}>Refresh</Button>} />
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}>
      <FormControl size="small" sx={{ minWidth: 200, flex: 1 }}>
        <InputLabel id="analytics-repository-label">Repository</InputLabel>
        <Select labelId="analytics-repository-label" label="Repository" value={repositoryId ?? ''}
          disabled={!repositories.length} onChange={(event) => setSelectedId(Number(event.target.value))}>
          {repositories.map((repo) => <MenuItem key={repo.id} value={repo.id}>{repo.fullName}</MenuItem>)}
        </Select>
      </FormControl>
      <FormControl size="small" sx={{ minWidth: 170 }}>
        <InputLabel id="analytics-range-label">Time range</InputLabel>
        <Select labelId="analytics-range-label" label="Time range" value={days}
          onChange={(event) => setDays(Number(event.target.value))}>
          {[7, 30, 90].map((period) => <MenuItem key={period} value={period}>Last {period} days</MenuItem>)}
        </Select>
      </FormControl>
      {catalog.hasNextPage && <Button disabled={catalog.isFetchingNextPage}
        onClick={() => void catalog.fetchNextPage()}>Load more repositories</Button>}
    </Stack>
    <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
      UTC calendar days, including today. Counts cover persisted workflow attempts, not all GitHub history.
      Failure rate = failed attempts / all completed attempts. Days without runs have no rate.
    </Typography>
    {catalog.isError && <Alert severity="error" action={<Button onClick={() => void catalog.refetch()}>Retry repositories</Button>} sx={{ mb: 2 }}>
      {repositoryErrorMessage(catalog.error)}
    </Alert>}
    {analytics.isError && <Alert severity="error" action={<Button onClick={() => void analytics.refetch()}>Retry analytics</Button>} sx={{ mb: 2 }}>
      {repositoryErrorMessage(analytics.error)}{data ? ' Showing previously loaded data.' : ''}
    </Alert>}
    {catalog.isPending && <Skeleton role="status" aria-label="Loading repositories" height={100} />}
    {catalog.isSuccess && !repositories.length && <Alert severity="info">
      No repositories recorded yet. Configure completed workflow webhooks to start collecting run metadata.
    </Alert>}
    {repositoryId !== undefined && <>
      {analytics.isFetching && <Typography role="status" sx={{ mb: 2 }}>Loading analytics...</Typography>}
      <Grid container spacing={2} sx={{ mb: 3 }}>
        {metrics.map((metric) => <Grid key={metric.label} size={{ xs: 12, sm: 6, lg: 3 }}>
          <Card sx={{ p: 2.5, height: '100%' }}>
            <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
              <Typography component="h2" variant="body2" color="text.secondary">{metric.label}</Typography>
              <MuiTooltip title={metric.help}><IconButton size="small" aria-label={`About ${metric.label.toLowerCase()}`}>
                <InfoOutlined fontSize="small" />
              </IconButton></MuiTooltip>
            </Stack>
            <Typography sx={{ fontSize: '2rem', fontWeight: 700, mt: 1 }}>
              {metric.value === undefined ? (analytics.isPending ? <Skeleton width={65} /> : '\u2014') : metric.value.toLocaleString()}
            </Typography>
          </Card>
        </Grid>)}
      </Grid>
      {data && <>
        {!data.totalRuns && <Alert severity="info" sx={{ mb: 3 }}>
          No workflow runs in this time range. Try a longer range or another repository. Historical successes are not backfilled automatically.
        </Alert>}
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, lg: 7 }} sx={{ minWidth: 0 }}>
            <SectionCard title="Daily workflow failure rate" subtitle="Percentage of persisted completed attempts per UTC day">
              {!data.totalRuns ? <ChartEmpty>No daily failure rates yet. Rates appear once completed runs are recorded.</ChartEmpty> :
                <Box role="region" aria-label="Daily workflow failure rate chart" sx={{ height: 320, width: '100%', minWidth: 0, pr: 2 }}>
                  <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 320, height: 320 }}>
                    <LineChart data={data.dailyRates} accessibilityLayer margin={{ top: 10, right: 12, bottom: 20, left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="date" tickFormatter={(date: string) => date.slice(5)} minTickGap={25} tick={{ fontSize: 11 }} />
                      <YAxis domain={[0, 100]} unit="%" width={55} tick={{ fontSize: 11 }} />
                      <Tooltip formatter={(value) => [`${Number(value).toFixed(1)}%`, 'Failure rate']}
                        labelFormatter={(label) => `UTC date: ${label}`} />
                      <Line type="linear" dataKey="failureRate" name="Failure rate" stroke="#7656b0" strokeWidth={2}
                        dot={{ r: 3 }} activeDot={{ r: 5 }} connectNulls={false} isAnimationActive={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </Box>}
            </SectionCard>
          </Grid>
          <Grid size={{ xs: 12, lg: 5 }} sx={{ minWidth: 0 }}>
            <SectionCard title="Failures by category" subtitle="Attempts with each available category; an attempt can have multiple categories">
              {!categories.length ? <ChartEmpty>{data.failedRuns
                ? 'No categorized failures yet. Analyses may be pending, failed or missing job logs.'
                : 'No failed workflows in this time range.'}</ChartEmpty> :
                <Box role="region" aria-label="Failures by category chart" sx={{ height: 320, width: '100%', minWidth: 0, pr: 2 }}>
                  <ResponsiveContainer width="100%" height="100%" initialDimension={{ width: 320, height: 320 }}>
                    <BarChart data={categories} layout="vertical" accessibilityLayer margin={{ top: 10, right: 20, bottom: 20, left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                      <XAxis type="number" allowDecimals={false} tick={{ fontSize: 11 }} />
                      <YAxis type="category" dataKey="category" width={145} tick={{ fontSize: 11 }} />
                      <Tooltip formatter={(value) => [value, 'Failed attempts']} />
                      <Bar dataKey="count" name="Failed attempts" fill="#a18ad4" radius={[0, 4, 4, 0]} isAnimationActive={false} />
                    </BarChart>
                  </ResponsiveContainer>
                </Box>}
            </SectionCard>
          </Grid>
        </Grid>
        <Box component="details" sx={{ mt: 3 }}>
          <Typography component="summary" sx={{ cursor: 'pointer' }}>View accessible chart data</Typography>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small" aria-label="Daily workflow failure rates">
              <TableHead><TableRow>{['UTC date', 'Total runs', 'Failed runs', 'Failure rate'].map((label) =>
                <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead>
              <TableBody>{data.dailyRates.map((day) => <TableRow key={day.date}>
                <TableCell component="th" scope="row">{day.date}</TableCell><TableCell>{day.totalRuns}</TableCell>
                <TableCell>{day.failedRuns}</TableCell><TableCell>{day.failureRate === null ? 'No runs' : `${day.failureRate.toFixed(1)}%`}</TableCell>
              </TableRow>)}</TableBody>
            </Table>
            <Table size="small" aria-label="Failures by category">
              <TableHead><TableRow><TableCell>Category</TableCell><TableCell>Failed attempts</TableCell></TableRow></TableHead>
              <TableBody>{Object.entries(data.failuresByCategory).map(([category, count]) => <TableRow key={category}>
                <TableCell component="th" scope="row">{categoryNames[category] ?? category}</TableCell><TableCell>{count}</TableCell>
              </TableRow>)}</TableBody>
            </Table>
          </Box>
        </Box>
      </>}
    </>}
  </>;
}
