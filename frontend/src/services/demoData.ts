export type RunStatus = 'Failed' | 'Passed' | 'Running';
export interface WorkflowRun {
  id: number;
  name: string;
  repository: string;
  branch: string;
  status: RunStatus;
  duration: string;
  triggered: string;
  cause?: string;
}

export const workflowRuns: WorkflowRun[] = [
  { id: 1842, name: 'CI / Build & Test', repository: 'frontend-app', branch: 'main', status: 'Failed', duration: '2m 34s', triggered: '12 minutes ago', cause: 'TypeScript compilation error in checkout module' },
  { id: 967, name: 'Deploy to Production', repository: 'api-service', branch: 'main', status: 'Passed', duration: '4m 12s', triggered: '28 minutes ago' },
  { id: 321, name: 'Integration Tests', repository: 'auth-service', branch: 'feat/oauth', status: 'Failed', duration: '3m 08s', triggered: '42 minutes ago', cause: 'OAuth integration test timeout' },
  { id: 1841, name: 'CI / Build & Test', repository: 'frontend-app', branch: 'feat/dashboard', status: 'Passed', duration: '2m 18s', triggered: '1 hour ago' },
  { id: 156, name: 'Docker Build & Push', repository: 'infrastructure', branch: 'main', status: 'Running', duration: '1m 45s', triggered: '1 hour ago' },
  { id: 966, name: 'Unit Tests', repository: 'api-service', branch: 'fix/validation', status: 'Failed', duration: '1m 52s', triggered: '2 hours ago', cause: 'Request validation assertion failed' },
  { id: 89, name: 'Publish Package', repository: 'shared-utils', branch: 'main', status: 'Failed', duration: '0m 48s', triggered: '3 hours ago', cause: 'Package registry credentials not configured' },
  { id: 155, name: 'Terraform Plan', repository: 'infrastructure', branch: 'feat/network', status: 'Failed', duration: '2m 03s', triggered: '4 hours ago', cause: 'Infrastructure plan validation failed' },
];

export const repositories = [
  { name: 'frontend-app', description: 'The customer-facing web application', language: 'TypeScript', color: '#668cc4', workflows: 4, success: 94.2, runs: 286 },
  { name: 'api-service', description: 'Core REST API and business services', language: 'Java', color: '#cb8e65', workflows: 3, success: 98.1, runs: 245 },
  { name: 'auth-service', description: 'Authentication and access management', language: 'Java', color: '#cb8e65', workflows: 3, success: 91.7, runs: 192 },
  { name: 'infrastructure', description: 'Cloud infrastructure and deployment', language: 'HCL', color: '#9473cf', workflows: 5, success: 96.8, runs: 174 },
  { name: 'shared-utils', description: 'Shared packages and developer utilities', language: 'TypeScript', color: '#668cc4', workflows: 2, success: 97.5, runs: 134 },
  { name: 'docs', description: 'Product documentation and guides', language: 'MDX', color: '#ccad5d', workflows: 1, success: 100, runs: 53 },
];

export const weeklyActivity = [44, 61, 48, 78, 63, 87, 69, 52, 73, 91, 68, 83, 59, 75];
export const monthlyActivity = [51, 65, 43, 57, 71, 61, 81, 69, 76, 88, 71, 82, 91, 85];
