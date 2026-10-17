import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    primary: { main: '#7255d8', light: '#f0ebfc', dark: '#563bb5' },
    background: { default: '#f8f9fc', paper: '#ffffff' },
    text: { primary: '#242938', secondary: '#7a8090' },
    divider: '#eceef3',
    success: { main: '#258b69' },
    error: { main: '#d95869' },
    warning: { main: '#ba852d' },
  },
  typography: {
    fontFamily: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h4: { fontSize: '1.75rem', fontWeight: 700, letterSpacing: '-0.8px' },
    h6: { fontSize: '1rem', fontWeight: 650, letterSpacing: '-0.2px' },
    subtitle2: { fontWeight: 600 },
    body1: { fontSize: '0.9rem' },
    body2: { fontSize: '0.8125rem', lineHeight: 1.7 },
    button: { textTransform: 'none', fontWeight: 600, fontSize: '0.8125rem' },
    overline: { fontSize: '0.625rem', fontWeight: 700, letterSpacing: '1.5px' },
  },
  shape: { borderRadius: 10 },
  components: {
    MuiCard: { defaultProps: { elevation: 0 }, styleOverrides: { root: { border: '1px solid #e8ebf1', borderRadius: 12 } } },
    MuiButton: { defaultProps: { disableElevation: true }, styleOverrides: { root: { borderRadius: 7, padding: '8px 14px' }, outlined: { borderColor: '#e1e4ec', color: '#50576a', backgroundColor: '#fff' } } },
    MuiChip: { styleOverrides: { root: { fontSize: '0.7rem', fontWeight: 600, borderRadius: 6, height: 24 } } },
    MuiTableCell: { styleOverrides: { root: { borderColor: '#f0f1f5', padding: '16px 22px', whiteSpace: 'nowrap', fontSize: '0.8rem' }, head: { color: '#858b9b', backgroundColor: '#fcfcfe', fontSize: '0.7rem', fontWeight: 500 } } },
    MuiAlert: { styleOverrides: { root: { borderRadius: 9, fontSize: '0.8rem' } } },
    MuiCssBaseline: { styleOverrides: { body: { margin: 0 }, '::selection': { background: '#e4dbff' }, a: { textDecoration: 'none' }, 'button, a, input': { WebkitTapHighlightColor: 'transparent' } } },
  },
});
