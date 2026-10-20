import { CfnOutput, Duration, RemovalPolicy, Stack } from 'aws-cdk-lib';
import { BlockPublicAccess, Bucket, BucketEncryption } from 'aws-cdk-lib/aws-s3';
import { Queue, QueueEncryption } from 'aws-cdk-lib/aws-sqs';
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

    const deadLetterQueue = new Queue(this, 'WorkflowEventsDeadLetterQueue', {
      encryption: QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      retentionPeriod: Duration.days(14),
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const events = new Queue(this, 'WorkflowEvents', {
      encryption: QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      visibilityTimeout: Duration.seconds(120),
      receiveMessageWaitTime: Duration.seconds(20),
      retentionPeriod: Duration.days(4),
      deadLetterQueue: { queue: deadLetterQueue, maxReceiveCount: 5 },
      removalPolicy: RemovalPolicy.RETAIN,
    });
    new CfnOutput(this, 'WorkflowQueueUrl', { value: events.queueUrl });
    new CfnOutput(this, 'WorkflowQueueArn', { value: events.queueArn });
    new CfnOutput(this, 'WorkflowDeadLetterQueueUrl', { value: deadLetterQueue.queueUrl });
  }
}
