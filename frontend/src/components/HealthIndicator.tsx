import { Box, Button, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import { RefreshRounded } from '@mui/icons-material';
import { useHealth } from '../hooks/useHealth';

export default function HealthIndicator({ detailed = false }: { detailed?: boolean }) {
  const health = useHealth();
  const label = health.isPending ? 'Checking backend' : health.isError ? 'Backend unreachable' :
    health.data.status === 'UP' ? 'Backend connected' : `Backend status: ${health.data.status}`;
  const color = health.isPending ? 'default' : health.isError ? 'error' : health.data.status === 'UP' ? 'success' : 'warning';

  return (
    <Stack spacing={1.5} role="status" aria-live="polite">
      <Stack direction="row" spacing={1}   useFlexGap sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Chip size="small" color={color} variant="outlined" label={label}
          icon={health.isFetching ? <CircularProgress size={12} color="inherit" /> : <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: `${color}.main`, ml: '9px !important' }} />} />
        {detailed && <Button size="small" startIcon={<RefreshRounded />} disabled={health.isFetching} onClick={() => void health.refetch()}>Check now</Button>}
      </Stack>
      {detailed && <>
        <Typography variant="body2" color="text.secondary">
          {health.isError ? `${health.error.message} Check the API URL, server, and CORS configuration.` :
            health.data ? `${health.data.service} · Reported ${health.data.timestamp}` : 'Contacting the Spring Boot health endpoint…'}
        </Typography>
        <Typography variant="caption" color="text.secondary">Automatically checked every 30 seconds. Health reflects application liveness only.</Typography>
      </>}
    </Stack>
  );
}
