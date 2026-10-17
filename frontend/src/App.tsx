import { createTheme, CssBaseline, ThemeProvider } from '@mui/material';
import MainLayout from './layouts/MainLayout';
import HomePage from './pages/HomePage';

const theme = createTheme();

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MainLayout>
        <HomePage />
      </MainLayout>
    </ThemeProvider>
  );
}
