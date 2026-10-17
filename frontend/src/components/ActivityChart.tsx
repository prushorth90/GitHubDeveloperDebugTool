import { useId } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { monthlyActivity, weeklyActivity } from '../services/demoData';

export default function ActivityChart({ period }: { period: string }) {
  const gradientId = useId();
  const values = period === '7' ? weeklyActivity : monthlyActivity;
  const points = values.map((v, i) => `${44 + i * 42},${206 - v * 1.65}`);
  const line = `M ${points.join(' L ')}`;
  return (
    <Box sx={{ px: 2.5, pb: 2 }}>
      <Stack direction="row" spacing={2}  sx={{justifyContent: "flex-end",  mb: 1 }}>
        {['Successful', 'Failed'].map((label, i) => <Stack key={label} direction="row" spacing={0.7}  sx={{ alignItems: "center" }}><Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: i ? '#e6a8b3' : 'primary.main' }} /><Typography sx={{ fontSize: 10, color: 'text.secondary' }}>{label}</Typography></Stack>)}
      </Stack>
      <Box component="svg" role="img" aria-label={`Demo workflow activity for the last ${period} days: successful runs trend upward, with fewer failed runs.`} viewBox="0 0 620 245" sx={{ width: '100%', height: 220, display: 'block', overflow: 'visible' }}>
        <defs><linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#9a80e5" stopOpacity=".16" /><stop offset="100%" stopColor="#9a80e5" stopOpacity="0" /></linearGradient></defs>
        {[0, 25, 50, 75, 100].map((tick) => <g key={tick}><line x1="40" x2="600" y1={206 - tick * 1.65} y2={206 - tick * 1.65} stroke="#eff0f5" strokeDasharray="3 4" /><text x="24" y={210 - tick * 1.65} textAnchor="end" fill="#9ba1b0" fontSize="9">{tick}</text></g>)}
        <path d={`${line} L 590,206 L 44,206 Z`} fill={`url(#${gradientId})`} />
        <path d={line} fill="none" stroke="#8b6cde" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />
        <path d={`M ${values.map((v, i) => `${44 + i * 42},${201 - ((v * 3 + i * 7) % 17)}`).join(' L ')}`} fill="none" stroke="#e4a6b1" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        {(period === '7' ? ['Oct 11', 'Oct 12', 'Oct 13', 'Oct 14', 'Oct 15', 'Oct 16', 'Oct 17'] : ['Sep 18', 'Sep 23', 'Sep 28', 'Oct 3', 'Oct 8', 'Oct 13', 'Oct 17']).map((date, i) => <text key={date} x={44 + i * 91} y="234" textAnchor="middle" fontSize="9" fill="#9ba1b0">{date}</text>)}
      </Box>
    </Box>
  );
}
