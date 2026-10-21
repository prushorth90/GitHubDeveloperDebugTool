import { Alert, Box, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import type { WorkflowFailure } from '../types/repositories';

type Analysis = NonNullable<WorkflowFailure['failedJobs'][number]['analysis']>;

function LogEvidence({ lines }: { lines: Analysis['evidence'] }) {
  return <Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: '0.8rem' }}>
    {lines.map((line) => `Line ${line.lineNumber}: ${line.text}`).join('\n')
      || 'No direct log evidence; investigation required.'}
  </Box>;
}

export function FailureAnalysisDetails({ analysis }: { analysis: Analysis }) {
  const observations = analysis.diagnosticFindings?.filter((finding) => finding.kind === 'OBSERVATION') ?? [];
  const hypotheses = analysis.diagnosticFindings?.filter((finding) => finding.kind === 'HYPOTHESIS') ?? [];

  return <Stack spacing={2} sx={{ mt: 1 }}>
    <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }} useFlexGap>
      <Chip label={analysis.category} />
      <Chip label={`Overall classification confidence: ${analysis.confidence}`} variant="outlined" />
      <Chip label={`Source: ${analysis.analysisSource ?? 'LEGACY_JAVA'}`} variant="outlined" />
      {analysis.modelIdentifier && <Chip label={`Model: ${analysis.modelIdentifier}`} variant="outlined" />}
    </Stack>
    <Typography variant="body2" color="text.secondary">
      Overall confidence rates the category evidence, not certainty about the root cause.
    </Typography>
    {analysis.fallbackReason && <Alert severity="warning">
      Bedrock explanation unavailable ({analysis.fallbackReason}). Showing Java analysis.
    </Alert>}
    {(!analysis.analysisSource || analysis.analysisSource === 'LEGACY_JAVA') && <Alert severity="info">
      Legacy analysis: confidence and evidence have not been reassessed with the current analyzer.
    </Alert>}
    <Card component="section" aria-label="Observations" variant="outlined">
      <CardContent>
        <Typography component="h4" variant="h6">Observations</Typography>
        <Typography variant="body2" sx={{ mb: 2 }}>
          Observation confidence concerns log text, not whether its claims are true or establish a root cause.
          An echoed error message and an explicit exit command do not establish that a real connection was attempted or failed.
        </Typography>
        {observations.length ? observations.map((finding, index) => <Box key={index} sx={{ mt: 2 }}>
          <Chip size="small" color="info" label={`Observation / ${finding.confidence}`} />
          <Typography sx={{ mt: 1, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{finding.description}</Typography>
          <Typography variant="subtitle2" sx={{ mt: 1 }}>Supporting sanitized job log references</Typography>
          <LogEvidence lines={finding.evidence} />
        </Box>) : <>
          <Typography variant="subtitle2">Observed log message (not proof of root cause)</Typography>
          <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{analysis.errorMessage}</Typography>
          <Typography variant="body2" sx={{ mt: 1 }}>No structured observations were stored.</Typography>
        </>}
      </CardContent>
    </Card>
    <Card component="section" aria-label="Unverified hypotheses" variant="outlined">
      <CardContent>
        <Typography component="h4" variant="h6">Unverified hypotheses</Typography>
        <Alert severity="warning" sx={{ my: 1 }}>
          These are possible explanations, not established facts. Cited log lines do not verify a root cause.
        </Alert>
        {hypotheses.length ? hypotheses.map((finding, index) => <Box key={index} sx={{ mt: 2 }}>
          <Chip size="small" color="warning" label={`Hypothesis (unverified) / ${finding.confidence}`} />
          <Typography sx={{ mt: 1, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{finding.description}</Typography>
          <Typography variant="subtitle2" sx={{ mt: 1 }}>Cited log references (not verification)</Typography>
          <LogEvidence lines={finding.evidence} />
        </Box>) : <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          Root-cause hypothesis (unverified): {analysis.suspectedCause}
        </Typography>}
      </CardContent>
    </Card>
    <Card component="section" aria-label="Recommendations" variant="outlined">
      <CardContent>
        <Typography component="h4" variant="h6">Recommendations</Typography>
        <Chip size="small" label="Suggested investigation" variant="outlined" sx={{ my: 1 }} />
        <Typography variant="body2" sx={{ mb: 1 }}>
          Investigation guidance, not a verified diagnosis or confirmed fix.
        </Typography>
        <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{analysis.suggestedInvestigation}</Typography>
      </CardContent>
    </Card>
    <Card component="section" aria-label="Classification evidence" variant="outlined">
      <CardContent>
        <Typography component="h4" variant="subtitle2">Classification evidence (sanitized job log lines)</Typography>
        <LogEvidence lines={analysis.evidence} />
      </CardContent>
    </Card>
  </Stack>;
}
