import { useState } from 'react';
import { Box, Grid, MenuItem, Select, Stack, Typography } from '@mui/material';
import { PageHeader } from '../components/PageHeader';
import DemoNotice from '../components/DemoNotice';
import { SectionCard } from '../components/DashboardCards';
import ActivityChart from '../components/ActivityChart';
import { repositories } from '../services/demoData';

export default function AnalyticsPage() {
  const [period, setPeriod] = useState('7');
  return <>
    <PageHeader title="Analytics" description="Spot patterns, improve reliability, and build with confidence."
      action={<Select value={period} onChange={(event) => setPeriod(event.target.value)} size="small" inputProps={{ 'aria-label': 'Analytics time range' }} sx={{ bgcolor: 'white', fontSize: 12 }}><MenuItem value="7">Last 7 days</MenuItem><MenuItem value="30">Last 30 days</MenuItem></Select>} />
    <DemoNotice />
    <Grid container spacing={3}>
      <Grid size={{ xs: 12, lg: 8 }}><SectionCard title="Workflow trends" subtitle={`Sample activity over the last ${period} days`}><ActivityChart period={period} /></SectionCard></Grid>
      <Grid size={{ xs: 12, lg: 4 }}><SectionCard title="Sample period summary"><Box sx={{ px: 3, pb: 3 }}>{[
        ['Total runs', period === '7' ? '1,084' : '4,326'], ['Success rate', period === '7' ? '96.4%' : '95.8%'], ['Average duration', period === '7' ? '3m 24s' : '3m 41s'],
      ].map(([label, value]) => <Stack key={label} direction="row"  sx={{justifyContent: "space-between",  py: 2, borderBottom: '1px solid', borderColor: 'divider' }}><Typography variant="body2" color="text.secondary">{label}</Typography><Typography variant="subtitle2">{value}</Typography></Stack>)}</Box></SectionCard></Grid>
      <Grid size={12}><SectionCard title="Repository reliability" subtitle="Sample success rates · Last 7 days"><Stack spacing={2.5} sx={{ p: 3, pt: 1 }}>
        {repositories.map((repository) => <Stack key={repository.name} direction="row" spacing={2}  sx={{ alignItems: "center" }}><Typography sx={{ fontSize: 12, width: { xs: 110, sm: 160 }, flexShrink: 0 }}>{repository.name}</Typography><Box sx={{ flex: 1, height: 9, bgcolor: '#f2eef9', borderRadius: 1 }}><Box sx={{ width: `${repository.success}%`, height: '100%', bgcolor: '#a18ad4', borderRadius: 1 }} /></Box><Typography sx={{ width: 46, fontSize: 11, color: 'text.secondary', textAlign: 'right' }}>{repository.success}%</Typography></Stack>)}
      </Stack></SectionCard></Grid>
    </Grid>
  </>;
}
