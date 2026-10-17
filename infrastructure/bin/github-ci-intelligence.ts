import { App } from 'aws-cdk-lib';
import { GitHubCiIntelligenceStack } from '../lib/github-ci-intelligence-stack';

const app = new App();

new GitHubCiIntelligenceStack(app, 'GitHubCiIntelligenceStack');
