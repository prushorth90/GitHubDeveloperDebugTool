import { Stack, Typography } from '@mui/material';

export default function HomePage() {
  return (
    <Stack spacing={2}>
      <Typography variant="h3" component="h1">
        GitHub CI Intelligence
      </Typography>
      <Typography color="text.secondary">
        Project scaffold ready. CI intelligence features are not implemented yet.
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Java 21 and Spring Boot / React and Material UI / AWS CDK
      </Typography>
    </Stack>
  );
}
