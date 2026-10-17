import { useState } from 'react';
import { Box, Card, Chip, Grid, InputAdornment, LinearProgress, Stack, TextField, Typography } from '@mui/material';
import { FolderOpenRounded, SearchRounded } from '@mui/icons-material';
import { PageHeader } from '../components/PageHeader';
import DemoNotice from '../components/DemoNotice';
import { repositories } from '../services/demoData';

export default function RepositoriesPage() {
  const [search, setSearch] = useState('');
  const visible = repositories.filter((repository) => repository.name.toLowerCase().includes(search.toLowerCase()));
  return <>
    <PageHeader title="Repositories" description="One workspace for every repository and every workflow." />
    <DemoNotice />
    <TextField size="small" label="Search repositories" value={search} onChange={(event) => setSearch(event.target.value)} slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchRounded sx={{ fontSize: 19 }} /></InputAdornment> } }} sx={{ mb: 3, width: { xs: '100%', sm: 320 }, bgcolor: 'white' }} />
    <Grid container spacing={2.5}>
      {visible.map((repository) => <Grid key={repository.name} size={{ xs: 12, sm: 6, xl: 4 }}>
        <Card sx={{ p: 3 }}>
          <Stack direction="row"   sx={{alignItems: "center", justifyContent: "space-between",  mb: 2 }}><FolderOpenRounded color="primary" /><Chip label="Sample" sx={{ bgcolor: '#f3f0fa', color: 'primary.main', fontSize: 9 }} /></Stack>
          <Typography variant="h6" component="h2">{repository.name}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1, minHeight: 44 }}>{repository.description}</Typography>
          <Stack direction="row"  spacing={0.75} sx={{alignItems: "center",  mt: 2, mb: 3 }}><Box sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: repository.color }} /><Typography variant="caption" color="text.secondary">{repository.language}</Typography><Typography variant="caption" color="text.secondary" sx={{ pl: 2 }}>{repository.workflows} workflows</Typography></Stack>
          <Stack direction="row"  sx={{justifyContent: "space-between",  mb: 1 }}><Typography variant="caption" color="text.secondary">Success rate</Typography><Typography variant="caption" sx={{ fontWeight: 600 }}>{repository.success}%</Typography></Stack>
          <LinearProgress variant="determinate" value={repository.success} color="success" sx={{ height: 5, borderRadius: 5, bgcolor: '#edf3ef' }} />
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>{repository.runs} sample runs · Last 7 days</Typography>
        </Card>
      </Grid>)}
    </Grid>
    {!visible.length && <Typography color="text.secondary" sx={{ py: 5 }}>No repositories match “{search}”.</Typography>}
  </>;
}
