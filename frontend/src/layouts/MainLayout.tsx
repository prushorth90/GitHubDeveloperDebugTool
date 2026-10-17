import { AppBar, Box, Container, Toolbar, Typography } from '@mui/material';
import type { PropsWithChildren } from 'react';

export default function MainLayout({ children }: PropsWithChildren) {
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="static">
        <Toolbar>
          <Typography variant="h6" component="span">
            GitHub CI Intelligence
          </Typography>
        </Toolbar>
      </AppBar>
      <Container component="main" maxWidth="md" sx={{ py: 6 }}>
        {children}
      </Container>
    </Box>
  );
}
