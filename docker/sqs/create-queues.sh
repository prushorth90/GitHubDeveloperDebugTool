#!/bin/sh
set -eu

attempt=0
until aws --endpoint-url "$SQS_ENDPOINT" sqs list-queues >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "SQS emulator did not become ready" >&2
    exit 1
  fi
  sleep 2
done

dlq_url=$(aws --endpoint-url "$SQS_ENDPOINT" sqs create-queue \
  --queue-name workflow-events-dlq --attributes MessageRetentionPeriod=1209600 \
  --query QueueUrl --output text)
dlq_arn=$(aws --endpoint-url "$SQS_ENDPOINT" sqs get-queue-attributes \
  --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
aws --endpoint-url "$SQS_ENDPOINT" sqs create-queue --queue-name workflow-events \
  --attributes "{\"VisibilityTimeout\":\"120\",\"ReceiveMessageWaitTimeSeconds\":\"20\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"
