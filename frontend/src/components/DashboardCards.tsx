import type { ReactNode } from 'react';
import { Box, Card, Stack, Typography } from '@mui/material';
import { TrendingUpRounded } from '@mui/icons-material';

export function SectionCard({ title, subtitle, action, children }: { title: string; subtitle?: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <Stack direction="row"    sx={{justifyContent: "space-between", alignItems: "center", gap: 1,  p: 2.5, pb: subtitle ? 1.5 : 2.5 }}>
        <Box><Typography component="h2" variant="h6">{title}</Typography>{subtitle && <Typography sx={{ fontSize: 11, color: 'text.secondary', mt: 0.5 }}>{subtitle}</Typography>}</Box>
        {action}
      </Stack>
      {children}
    </Card>
  );
}

export function StatCard({ label, value, change, caption, icon, color, spark }: {
  label: string; value: string; change: string; caption: string; icon: ReactNode; color: string; spark: number[];
}) {
  return (
    <Card sx={{ p: 2.5, position: 'relative', overflow: 'hidden' }}>
      <Stack direction="row"   sx={{ justifyContent: "space-between", alignItems: "center" }}>
        <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>{label}</Typography>
        <Box sx={{ bgcolor: `${color}12`, color, width: 33, height: 33, borderRadius: 2, display: 'grid', placeItems: 'center', '& svg': { fontSize: 18 } }}>{icon}</Box>
      </Stack>
      <Stack direction="row"   sx={{justifyContent: "space-between", alignItems: "center",  my: 1.5 }}>
        <Typography sx={{ fontSize: '1.9rem', fontWeight: 700, letterSpacing: '-1px' }}>{value}</Typography>
        <Box component="svg" viewBox="0 0 100 40" aria-hidden="true" sx={{ width: 88, height: 40 }}>
          <polyline points={spark.map((v, i) => `${i * 10},${40 - v}`).join(' ')} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </Box>
      </Stack>
      <Stack direction="row" spacing={0.5}  sx={{ alignItems: "center" }}>
        <TrendingUpRounded sx={{ color: 'success.main', fontSize: 13 }} />
        <Typography sx={{ color: 'success.main', fontSize: 10, fontWeight: 600 }}>{change}</Typography>
        <Typography sx={{ color: 'text.secondary', fontSize: 10 }}>{caption}</Typography>
      </Stack>
    </Card>
  );
}
