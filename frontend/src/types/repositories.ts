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

export interface WorkflowFailure {
  run: WorkflowRun;
  failedJobs: {
    jobId: number;
    name: string;
    logStatus: 'AVAILABLE' | 'UNAVAILABLE';
    logMessage: string | null;
    analysis: {
      category: FailureCategory;
      errorMessage: string;
      suspectedCause: string;
      suggestedInvestigation: string;
      confidence: 'LOW' | 'MEDIUM' | 'HIGH';
      analysisSource?: 'JAVA' | 'LEGACY_JAVA' | 'BEDROCK';
      modelIdentifier?: string | null;
      fallbackReason?: string | null;
      diagnosticFindings?: {
        kind: 'OBSERVATION' | 'HYPOTHESIS';
        description: string;
        confidence: 'LOW' | 'MEDIUM' | 'HIGH';
        evidence: { lineNumber: number; text: string }[];
      }[];
      evidence: { lineNumber: number; text: string }[];
    } | null;
  }[];
}

export interface WorkflowAnalytics {
  from: string;
  until: string;
  totalRuns: number;
  failedRuns: number;
  recurringFailures: number;
  successfulAnalyses: number;
  dailyRates: {
    date: string;
    totalRuns: number;
    failedRuns: number;
    failureRate: number | null;
  }[];
  failuresByCategory: Record<FailureCategory, number>;
}
