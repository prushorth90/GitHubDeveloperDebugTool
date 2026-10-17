import { Box, Chip, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { AccountTreeOutlined, CheckCircleOutlineRounded, ErrorOutlineRounded, SyncRounded } from '@mui/icons-material';
import type { WorkflowRun, RunStatus } from '../services/demoData';

export function StatusChip({ status }: { status: RunStatus }) {
  const color = status === 'Passed' ? '#258b69' : status === 'Failed' ? '#cf586a' : '#b8872e';
  const Icon = status === 'Passed' ? CheckCircleOutlineRounded : status === 'Failed' ? ErrorOutlineRounded : SyncRounded;
  return <Chip label={status} icon={<Icon sx={{ fontSize: '13px !important', color: `${color} !important` }} />} sx={{ bgcolor: `${color}0d`, color, height: 23, border: `1px solid ${color}20` }} />;
}

export function WorkflowTable({ runs, showCause = false }: { runs: WorkflowRun[]; showCause?: boolean }) {
  return (
    <TableContainer>
      <Table aria-label={showCause ? 'Demo workflow failures' : 'Demo recent workflow runs'} size="small">
        <TableHead><TableRow>
          <TableCell>Workflow / Repository</TableCell><TableCell>Branch</TableCell><TableCell>Status</TableCell>
          {showCause && <TableCell>Failure reason</TableCell>}
          <TableCell>Duration</TableCell><TableCell>Triggered</TableCell>
        </TableRow></TableHead>
        <TableBody>
          {runs.map((run) => <TableRow key={`${run.repository}-${run.id}`} hover>
            <TableCell>
              <Stack direction="row" spacing={1.25}  sx={{ alignItems: "center" }}>
                <Box sx={{ width: 30, height: 30, display: 'grid', placeItems: 'center', border: '1px solid', borderColor: 'divider', borderRadius: 1.5, color: '#979dac' }}><AccountTreeOutlined sx={{ fontSize: 16 }} /></Box>
                <Box><Typography sx={{ fontSize: 11.5, fontWeight: 600 }}>{run.name}</Typography><Typography sx={{ fontSize: 10, color: 'text.secondary', mt: 0.3 }}>{run.repository} <Box component="span" sx={{ color: '#b3b7c3' }}>#{run.id}</Box></Typography></Box>
              </Stack>
            </TableCell>
            <TableCell><Chip icon={<AccountTreeOutlined sx={{ fontSize: '12px !important' }} />} label={run.branch} sx={{ height: 22, fontFamily: 'monospace', fontSize: 10, bgcolor: '#f5f6f9', color: '#798094', fontWeight: 400 }} /></TableCell>
            <TableCell><StatusChip status={run.status} /></TableCell>
            {showCause && <TableCell sx={{ whiteSpace: 'normal', minWidth: 200 }}>{run.cause}</TableCell>}
            <TableCell sx={{ color: 'text.secondary', fontSize: 11 }}>{run.duration}</TableCell>
            <TableCell sx={{ color: 'text.secondary', fontSize: 11 }}>{run.triggered}</TableCell>
          </TableRow>)}
          {runs.length === 0 && <TableRow><TableCell colSpan={showCause ? 6 : 5} align="center" sx={{ py: 5 }}>No demo workflows match your search.</TableCell></TableRow>}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
