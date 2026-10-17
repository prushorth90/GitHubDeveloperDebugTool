import { Alert } from '@mui/material';

export default function DemoNotice() {
  return <Alert severity="info" variant="outlined" sx={{ mb: 3, bgcolor: '#f5f3fc', borderColor: '#e9e3f8', color: '#756393', '& .MuiAlert-icon': { color: '#957bc6' } }}>You're exploring a demo workspace. Metrics, repositories, and workflow runs are sample data. Only backend health is live.</Alert>;
}
