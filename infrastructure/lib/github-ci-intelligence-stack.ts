import { CfnOutput, Duration, RemovalPolicy, Stack } from 'aws-cdk-lib';
import { BlockPublicAccess, Bucket, BucketEncryption } from 'aws-cdk-lib/aws-s3';
import type { StackProps } from 'aws-cdk-lib';
import type { Construct } from 'constructs';

export class GitHubCiIntelligenceStack extends Stack {
  constructor(scope: Construct, id: string, props?: StackProps) {
    super(scope, id, props);

    const logs = new Bucket(this, 'JobLogs', {
      blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
      encryption: BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      versioned: false,
      removalPolicy: RemovalPolicy.RETAIN,
      lifecycleRules: [{
        id: 'ExpireJobLogsAfter30Days',
        prefix: 'logs/',
        expiration: Duration.days(30),
        abortIncompleteMultipartUploadAfter: Duration.days(1),
      }],
    });
    new CfnOutput(this, 'LogBucketName', { value: logs.bucketName });
  }
}
