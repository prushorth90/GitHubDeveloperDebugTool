import { useState } from 'react';
import { MenuItem, Select, Stack, Typography } from '@mui/material';
import { PageHeader } from '../components/PageHeader';
import DemoNotice from '../components/DemoNotice';
import { SectionCard } from '../components/DashboardCards';
import { WorkflowTable } from '../components/WorkflowTable';
import { repositories, workflowRuns } from '../services/demoData';

export default function FailuresPage() {
  const [repository, setRepository] = useState('all');
  const failures = workflowRuns.filter((run) => run.status === 'Failed' && (repository === 'all' || run.repository === repository));
  return <>
    <PageHeader title="Workflow Failures" description="Find the signal in the noise. Get your pipelines back on track." />
    <DemoNotice />
    <Stack direction="row"   sx={{justifyContent: "space-between", alignItems: "center", gap: 2,  mb: 3 }} >
      <Typography variant="body2" color="text.secondary">{failures.length} sample failures</Typography>
      <Select size="small" value={repository} onChange={(event) => setRepository(event.target.value)} inputProps={{ 'aria-label': 'Filter by repository' }} sx={{ bgcolor: 'white', minWidth: 180, fontSize: 12 }}>
        <MenuItem value="all">All repositories</MenuItem>
        {repositories.map((item) => <MenuItem key={item.name} value={item.name}>{item.name}</MenuItem>)}
      </Select>
    </Stack>
    <SectionCard title="Failed workflow runs" subtitle="Sample failure reasons for your development preview">
      <WorkflowTable runs={failures} showCause />
    </SectionCard>
  </>;
}
