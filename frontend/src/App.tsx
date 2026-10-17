import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import OverviewPage from './pages/OverviewPage';
import RepositoriesPage from './pages/RepositoriesPage';
import FailuresPage from './pages/FailuresPage';
import AnalyticsPage from './pages/AnalyticsPage';
import SettingsPage from './pages/SettingsPage';
import { theme } from './theme';
import { PageHeader } from './components/PageHeader';
import { Button } from '@mui/material';
import { Link } from 'react-router-dom';

const queryClient = new QueryClient();

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <Routes>
            <Route element={<MainLayout />}>
              <Route index element={<OverviewPage />} />
              <Route path="repositories" element={<RepositoriesPage />} />
              <Route path="workflow-failures" element={<FailuresPage />} />
              <Route path="analytics" element={<AnalyticsPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<>
                <PageHeader title="Page not found" description="This page doesn't exist. Head back to your dashboard." />
                <Button component={Link} to="/" variant="contained">Back to overview</Button>
              </>} />
            </Route>
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
