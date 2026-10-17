import { useState } from 'react';
import { Alert, Box, Button, Chip, Grid, MenuItem, Select, Stack, Typography } from '@mui/material';
import { ArrowForwardRounded, CalendarTodayOutlined, CheckCircleOutlineRounded, ErrorOutlineRounded, FolderOpenRounded, PlayCircleOutlineRounded, ScheduleRounded } from '@mui/icons-material';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components/PageHeader';
import { SectionCard, StatCard } from '../components/DashboardCards';
import ActivityChart from '../components/ActivityChart';
import DemoNotice from '../components/DemoNotice';
import HealthIndicator from '../components/HealthIndicator';
import { WorkflowTable } from '../components/WorkflowTable';
import { workflowRuns } from '../services/demoData';
import { useHealth } from '../hooks/useHealth';

export default function OverviewPage() {
  const [period, setPeriod] = useState('7');
  const health = useHealth();
  return (
    <>
      <PageHeader title="Overview" description="Your CI at a glance. Stay on top of what matters."
        action={<Select value={period} onChange={(event) => setPeriod(event.target.value)} size="small" startAdornment={<CalendarTodayOutlined sx={{ fontSize: 16, mr: 1, color: 'text.secondary' }} />} inputProps={{ 'aria-label': 'Overview time range' }} sx={{ fontSize: 12, bgcolor: 'white', minWidth: 155, height: 38 }}><MenuItem value="7">Last 7 days</MenuItem><MenuItem value="30">Last 30 days</MenuItem></Select>} />
      <DemoNotice />
      <Grid container spacing={2.25} sx={{ mb: 3 }}>
        {[
          { label: 'Repositories', value: '6', change: '+2', caption: 'from previous period', icon: <FolderOpenRounded />, color: '#8460cd', spark: [7, 7, 15, 15, 12, 20, 20, 29, 27, 33, 33] },
          { label: 'Workflow runs', value: period === '7' ? '1,084' : '4,326', change: '+12.8%', caption: 'from previous period', icon: <PlayCircleOutlineRounded />, color: '#6087ca', spark: [7, 12, 8, 22, 14, 17, 15, 30, 23, 29, 35] },
          { label: 'Success rate', value: period === '7' ? '96.4%' : '95.8%', change: '+2.1%', caption: 'from previous period', icon: <CheckCircleOutlineRounded />, color: '#4d9e87', spark: [10, 10, 18, 15, 24, 21, 27, 25, 31, 30, 35] },
          { label: 'Avg. build time', value: period === '7' ? '3m 24s' : '3m 41s', change: '18% faster', caption: 'than previous period', icon: <ScheduleRounded />, color: '#ca9c54', spark: [34, 30, 32, 25, 28, 20, 23, 15, 19, 13, 10] },
        ].map((stat) => <Grid key={stat.label} size={{ xs: 12, sm: 6, xl: 3, lg: 3 }}><StatCard {...stat} /></Grid>)}
      </Grid>
      <Grid container spacing={2.5} sx={{ mb: 3 }}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <SectionCard title="Workflow activity" subtitle="A daily view of your workflow runs" action={<Chip label="Sample data" size="small" sx={{ bgcolor: '#f6f5f9', color: '#9490a2', fontSize: 9 }} />}>
            <ActivityChart period={period} />
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 4 }}>
          <SectionCard title="Failure breakdown" subtitle="Understand what's slowing you down">
            <Stack direction="row"  spacing={2.5} sx={{alignItems: "center",  px: 2.5, py: 3.5 }}>
              <Box role="img" aria-label="39 sample failures: 46% build errors, 28% test failures, 18% deployment, 8% other" sx={{ position: 'relative', width: 132, height: 132, borderRadius: '50%', background: 'conic-gradient(#8a6bd1 0% 46%, #b8a3e6 46% 74%, #d6c9ed 74% 92%, #ede7f6 92% 100%)', flexShrink: 0, '&::after': { content: '""', position: 'absolute', inset: 14, bgcolor: 'white', borderRadius: '50%' } }}>
                <Box sx={{ position: 'absolute', inset: 0, zIndex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column' }}><Typography sx={{ fontSize: 27, fontWeight: 700, lineHeight: 1.2 }}>39</Typography><Typography sx={{ color: 'text.secondary', fontSize: 10 }}>total failures</Typography></Box>
              </Box>
              <Stack spacing={1.5}  sx={{ flex: 1 }}>
                {[['Build errors', '46%', '#8a6bd1'], ['Test failures', '28%', '#b8a3e6'], ['Deployment', '18%', '#d6c9ed'], ['Other', '8%', '#ede7f6']].map(([label, value, color]) => <Stack key={label} direction="row" spacing={0.8}  sx={{ alignItems: "center" }}><Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: color }} /><Typography sx={{ fontSize: 10, color: 'text.secondary', flex: 1 }}>{label}</Typography><Typography sx={{ fontSize: 10, fontWeight: 600 }}>{value}</Typography></Stack>)}
              </Stack>
            </Stack>
            <Box sx={{ mx: 2.5, pb: 2, pt: 1.5, borderTop: '1px solid', borderColor: 'divider' }}><Typography sx={{ fontSize: 10, color: 'text.secondary' }}>Build errors account for most failures this period.</Typography></Box>
          </SectionCard>
        </Grid>
      </Grid>
      <SectionCard title="Recent workflow runs" subtitle="The latest activity across your repositories" action={<Button component={Link} to="/workflow-failures" endIcon={<ArrowForwardRounded />} size="small" sx={{ fontSize: 11, whiteSpace: 'nowrap' }}>View failures</Button>}>
        <WorkflowTable runs={workflowRuns.slice(0, 5)} />
        <Stack direction="row"   sx={{alignItems: "center", justifyContent: "space-between",  px: 2.5, py: 1.5 }}>
          <Typography sx={{ fontSize: 10, color: 'text.secondary' }}>Showing 5 recent sample runs</Typography>
          <Chip label="Demo workspace" variant="outlined" sx={{ fontSize: 9, color: 'text.secondary', borderColor: 'divider', height: 20 }} />
        </Stack>
      </SectionCard>
      <Grid container spacing={2.5} sx={{ mt: 3 }}>
        <Grid size={{ xs: 12, md: 7 }}>
          <SectionCard title="Backend connection" action={<Box sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: health.isPending ? '#c6cad5' : health.isError ? 'error.main' : health.data.status === 'UP' ? 'success.main' : 'warning.main' }} />}>
            <Box sx={{ px: 2.5, pb: 2.5 }}><HealthIndicator detailed /></Box>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, md: 5 }}>
          <Alert icon={<ErrorOutlineRounded />} severity="warning" sx={{ height: '100%', bgcolor: '#fffcf5', border: '1px solid #f1e9d6', py: 2, '& .MuiAlert-message': { width: '100%' } }}>
            <Typography variant="subtitle2" sx={{ fontSize: 12, color: '#766540', mb: 0.5 }}>A little attention goes a long way</Typography>
            <Typography sx={{ fontSize: 11, color: '#9b8d73' }}>5 sample workflow failures are ready to investigate.</Typography>
            <Button component={Link} to="/workflow-failures" size="small" endIcon={<ArrowForwardRounded />} sx={{ px: 0, mt: 1, color: '#a17e38', fontSize: 11 }}>Review failures</Button>
          </Alert>
        </Grid>
      </Grid>
    </>
  );
}
