export interface ApiResponse<T> {
  data: T;
}

export interface ApiPage<T> {
  items: T[];
  nextToken: string | null;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

export interface Repository {
  id: number;
  name: string;
  fullName: string;
  htmlUrl: string | null;
  privateRepository: boolean;
}

export type FailureCategory =
  | 'JAVA_COMPILATION'
  | 'MAVEN_DEPENDENCY'
  | 'JUNIT_TEST'
  | 'POSTGRESQL_CONNECTION'
  | 'DOCKER_BUILD'
  | 'AUTHENTICATION'
  | 'GITHUB_ACTIONS_CONFIGURATION'
  | 'UNKNOWN';

export interface WorkflowRun {
  runId: number;
  attempt: number;
  workflowName: string | null;
  branch: string | null;
  conclusion: string | null;
  htmlUrl: string | null;
  createdAt: string;
  analyzedAt: string | null;
  analysisStatus: 'PROCESSING' | 'FAILED' | 'COMPLETED';
  analysisComplete: boolean;
  categories: FailureCategory[];
}

export interface RepositoryStatistics {
  totalRuns: number;
  completedAnalyses: number;
  processingAnalyses: number;
  failedAnalyses: number;
  failureRuns: number;
  unavailableJobAnalyses: number;
  failuresByCategory: Record<FailureCategory, number>;
  latestRun: WorkflowRun | null;
  lastAnalyzedAt: string | null;
}
