import { useState } from 'react';
import { AppBar, Avatar, Box, Button, Chip, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Stack, Toolbar, Typography } from '@mui/material';
import { ArrowOutwardRounded, AutoAwesomeRounded, BarChartRounded, ChevronRightRounded, CodeRounded, DashboardRounded, ErrorOutlineRounded, FolderOpenRounded, HelpOutlineRounded, MenuRounded, SettingsOutlined } from '@mui/icons-material';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import HealthIndicator from '../components/HealthIndicator';

const drawerWidth = 248;
const navigation = [
  { label: 'Overview', path: '/', icon: DashboardRounded },
  { label: 'Repositories', path: '/repositories', icon: FolderOpenRounded },
  { label: 'Workflow Failures', path: '/workflow-failures', icon: ErrorOutlineRounded },
  { label: 'Analytics', path: '/analytics', icon: BarChartRounded },
  { label: 'Settings', path: '/settings', icon: SettingsOutlined },
];

export default function MainLayout() {
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const currentPage = navigation.find((item) => item.path === location.pathname)?.label ?? 'Not found';
  const sidebar = (
    <Stack sx={{ height: '100%', px: 2.25 }}>
      <Stack direction="row"  spacing={1.25} sx={{alignItems: "center",  height: 88, px: 1 }}>
        <Box sx={{ bgcolor: 'primary.main', color: 'white', width: 35, height: 38, borderRadius: 2, display: 'grid', placeItems: 'center', boxShadow: '0 4px 12px #7255d830' }}><CodeRounded /></Box>
        <Box><Typography sx={{ fontWeight: 750, fontSize: '1rem', letterSpacing: '-0.5px' }}>GitHub CI</Typography><Typography sx={{ fontSize: '0.65rem', color: 'text.secondary', letterSpacing: '1.5px' }}>INTELLIGENCE</Typography></Box>
      </Stack>
      <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, px: 1.5, py: 1.25, mb: 3.5, mt: 1 }}>
        <Stack direction="row" spacing={1.1}  sx={{ alignItems: "center" }}>
          <Avatar variant="rounded" sx={{ width: 28, height: 28, bgcolor: '#eee9fa', color: 'primary.main', fontSize: 12, fontWeight: 700 }}>D</Avatar>
          <Box  sx={{ flex: 1 }}><Typography variant="subtitle2" sx={{ fontSize: 12 }}>Developer workspace</Typography><Typography sx={{ color: 'text.secondary', fontSize: 10 }}>Local environment</Typography></Box>
          <Chip label="DEV" sx={{ fontSize: 9, height: 19, bgcolor: '#f3f4f7', color: 'text.secondary' }} />
        </Stack>
      </Box>
      <Typography variant="overline" color="text.secondary" sx={{ px: 1.5, mb: 1 }}>Workspace</Typography>
      <List component="nav" aria-label="Main navigation" disablePadding>
        {navigation.map(({ label, path, icon: Icon }) => (
          <ListItemButton key={path} component={NavLink} to={path} end={path === '/'} onClick={() => setMobileOpen(false)}
            sx={{ borderRadius: 2, mb: 0.75, minHeight: 44, px: 1.5, color: '#737b8e', '&.active': { bgcolor: '#eee8fc', color: 'primary.main', '& .MuiListItemIcon-root': { color: 'primary.main' }, '& .MuiListItemText-primary': { fontWeight: 650 } } }}>
            <ListItemIcon sx={{ minWidth: 33, color: 'inherit' }}><Icon sx={{ fontSize: 20 }} /></ListItemIcon>
            <ListItemText primary={label} slotProps={{ primary: { sx: { fontSize: 12.5 } } }} />
          </ListItemButton>
        ))}
      </List>
      <Box sx={{ flexGrow: 1, minHeight: 40 }} />
      <Box sx={{ p: 2, bgcolor: '#f6f3fe', border: '1px solid #eee8fc', borderRadius: 2.5, mb: 2.5 }}>
        <AutoAwesomeRounded sx={{ color: 'primary.main', fontSize: 21, mb: 1 }} />
        <Typography variant="subtitle2" sx={{ fontSize: 12, mb: 0.5 }}>Less debugging. More building.</Typography>
        <Typography sx={{ color: 'text.secondary', fontSize: 11, lineHeight: 1.8, mb: 1.25 }}>A clearer picture of your CI, all in one workspace.</Typography>
        <Button component="a" href="https://docs.github.com/en/actions" target="_blank" rel="noreferrer" endIcon={<ArrowOutwardRounded sx={{ fontSize: '14px !important' }} />} size="small" sx={{ px: 0, fontSize: 11 }}>Explore Actions docs</Button>
      </Box>
      <Divider />
      <Stack direction="row"  spacing={1.2} sx={{alignItems: "center",  py: 2.25, px: 0.5 }}>
        <Avatar sx={{ width: 32, height: 32, bgcolor: '#eae4f7', color: '#6a54a6', fontSize: 12, fontWeight: 700 }}>DV</Avatar>
        <Box  sx={{ flex: 1 }}><Typography variant="subtitle2" sx={{ fontSize: 12 }}>Developer</Typography><Typography sx={{ fontSize: 10, color: 'text.secondary' }}>Personal workspace</Typography></Box>
        <IconButton component={Link} to="/settings" size="small" aria-label="Open settings"><SettingsOutlined sx={{ fontSize: 18 }} /></IconButton>
      </Stack>
    </Stack>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Box component="a" href="#main-content" sx={{ position: 'fixed', top: -80, left: 16, zIndex: 1500, p: 1.5, bgcolor: 'white', '&:focus': { top: 8 } }}>Skip to content</Box>
      <Drawer variant="permanent" sx={{ display: { xs: 'none', md: 'block' }, width: drawerWidth, flexShrink: 0, '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box', borderColor: 'divider' } }}>{sidebar}</Drawer>
      <Drawer variant="temporary" open={mobileOpen} onClose={() => setMobileOpen(false)} sx={{ display: { xs: 'block', md: 'none' }, '& .MuiDrawer-paper': { width: drawerWidth } }} slotProps={{ paper: { 'aria-label': 'Mobile navigation' } }}>{sidebar}</Drawer>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <AppBar position="sticky" elevation={0} color="inherit" sx={{ bgcolor: 'rgba(255,255,255,.96)', borderBottom: '1px solid', borderColor: 'divider' }}>
          <Toolbar sx={{ minHeight: '72px !important', px: { xs: 2, lg: '36px !important' }, gap: 1 }}>
            <IconButton aria-label="Open navigation" onClick={() => setMobileOpen(true)} edge="start" sx={{ display: { md: 'none' } }}><MenuRounded /></IconButton>
            <Typography variant="body2" color="text.secondary" sx={{ display: { xs: 'none', sm: 'block' } }}>Workspace</Typography>
            <ChevronRightRounded sx={{ fontSize: 15, color: '#b8bdc9', mx: 0.75, display: { xs: 'none', sm: 'block' } }} />
            <Typography variant="body2" sx={{ fontWeight: 550 }}>{currentPage}</Typography>
            <Box  sx={{ flex: 1 }} />
            <Box sx={{ display: { xs: 'none', sm: 'block' } }}><HealthIndicator /></Box>
            <Divider orientation="vertical" flexItem sx={{ my: 2.5, mx: 1.5 }} />
            <IconButton component="a" href="https://docs.github.com/en/actions" target="_blank" rel="noreferrer" aria-label="GitHub Actions documentation" size="small"><HelpOutlineRounded sx={{ fontSize: 20 }} /></IconButton>
            <Avatar sx={{ ml: 1, width: 30, height: 30, fontSize: 10, fontWeight: 700, bgcolor: '#ede7f8', color: '#7961ad' }}>DV</Avatar>
          </Toolbar>
        </AppBar>
        <Box component="main" id="main-content" sx={{ px: { xs: 2, sm: 3, lg: 4.5 }, py: { xs: 3, lg: 4 }, maxWidth: 1600, mx: 'auto' }}>
          <Outlet />
          <Stack component="footer" direction={{ xs: 'column', sm: 'row' }}   sx={{justifyContent: "space-between", gap: 1,  mt: 4, pt: 2, borderTop: '1px solid', borderColor: 'divider' }}>
            <Typography sx={{ fontSize: 10, color: '#9a9fae' }}>GitHub CI Intelligence <Box component="span" sx={{ mx: 1 }}>·</Box> Built for a better developer workflow</Typography>
            <Typography sx={{ fontSize: 10, color: '#9a9fae' }}>Development preview <Box component="span" sx={{ mx: 0.75 }}>·</Box> v0.1.0</Typography>
          </Stack>
        </Box>
      </Box>
    </Box>
  );
}
