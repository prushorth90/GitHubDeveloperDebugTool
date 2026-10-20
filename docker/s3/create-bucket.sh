#!/bin/sh
set -eu

attempt=0
until aws --endpoint-url "$S3_ENDPOINT" s3api list-buckets >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "S3 emulator did not become ready" >&2
    exit 1
  fi
  sleep 2
done

if ! aws --endpoint-url "$S3_ENDPOINT" s3api head-bucket --bucket "$S3_LOG_BUCKET"; then
  if [ "$AWS_DEFAULT_REGION" = "us-east-1" ]; then
    aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$S3_LOG_BUCKET"
  else
    aws --endpoint-url "$S3_ENDPOINT" s3api create-bucket --bucket "$S3_LOG_BUCKET" \
      --create-bucket-configuration "LocationConstraint=$AWS_DEFAULT_REGION"
  fi
fi
aws --endpoint-url "$S3_ENDPOINT" s3api put-bucket-lifecycle-configuration \
  --bucket "$S3_LOG_BUCKET" \
  --lifecycle-configuration '{"Rules":[{"ID":"ExpireJobLogsAfter30Days","Status":"Enabled","Filter":{"Prefix":"logs/"},"Expiration":{"Days":30},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
