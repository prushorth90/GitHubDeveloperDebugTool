import { Alert, Box, Grid, Stack, TextField, Typography } from '@mui/material';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/DashboardCards';
import HealthIndicator from '../components/HealthIndicator';
import { apiBaseUrl } from '../services/api';

export default function SettingsPage() {
  return <>
    <PageHeader title="Settings" description="Your workspace configuration and backend connection." />
    <Grid container spacing={3}>
      <Grid size={{ xs: 12, lg: 7 }}><SectionCard title="API configuration" subtitle="Centralized Axios client · Spring Boot backend">
        <Stack spacing={2.5} sx={{ p: 3, pt: 1 }}>
          <TextField label="API base URL" value={apiBaseUrl} fullWidth size="small" slotProps={{ input: { readOnly: true } }} helperText="Configured at build time with VITE_API_BASE_URL." />
          <TextField label="Health endpoint" value="/api/health" fullWidth size="small" slotProps={{ input: { readOnly: true } }} />
          <Alert severity="info">To change the backend URL, set VITE_API_BASE_URL in your frontend .env.local file and restart Vite. Production builds must be rebuilt. VITE_* values are public; never include credentials.</Alert>
        </Stack>
      </SectionCard></Grid>
      <Grid size={{ xs: 12, lg: 5 }}><SectionCard title="Connection status"><Box sx={{ px: 3, pb: 3 }}><HealthIndicator detailed /></Box></SectionCard></Grid>
      <Grid size={12}><SectionCard title="About this workspace"><Stack spacing={1.5} sx={{ px: 3, pb: 3 }}>
        <Typography variant="body2" color="text.secondary">GitHub CI Intelligence · Development preview v0.1.0</Typography>
        <Typography variant="body2" color="text.secondary">Repository connections, GitHub authentication, and CI analysis APIs are not implemented yet. Dashboard data is illustrative and is not stored or sent to the backend.</Typography>
      </Stack></SectionCard></Grid>
    </Grid>
  </>;
}
