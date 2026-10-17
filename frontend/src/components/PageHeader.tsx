import { Box, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export function PageHeader({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }}    sx={{justifyContent: "space-between", alignItems: { sm: 'center' }, gap: 2,  mb: 3.5 }}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ mb: 0.75 }}>{title}</Typography>
        <Typography variant="body2" color="text.secondary">{description}</Typography>
      </Box>
      {action}
    </Stack>
  );
}
