#!/bin/sh
set -eu

attempt=0
until aws dynamodb list-tables --endpoint-url "$DYNAMODB_ENDPOINT" >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "DynamoDB Local did not become ready" >&2
    exit 1
  fi
  sleep 2
done

tables=$(aws dynamodb list-tables --endpoint-url "$DYNAMODB_ENDPOINT" \
  --query 'TableNames[]' --output text)
exists=false
for table in $tables; do
  if [ "$table" = "$DYNAMODB_TABLE_NAME" ]; then
    exists=true
  fi
done

if [ "$exists" = false ]; then
  aws dynamodb create-table --endpoint-url "$DYNAMODB_ENDPOINT" \
    --table-name "$DYNAMODB_TABLE_NAME" \
    --billing-mode PAY_PER_REQUEST \
    --attribute-definitions \
      AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
      AttributeName=failurePk,AttributeType=S AttributeName=failureSk,AttributeType=S \
      AttributeName=runSk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
    --global-secondary-indexes \
      '[{"IndexName":"recent-failures","KeySchema":[{"AttributeName":"failurePk","KeyType":"HASH"},{"AttributeName":"failureSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},{"IndexName":"repository-runs","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"runSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}]'
else
  index=$(aws dynamodb describe-table --endpoint-url "$DYNAMODB_ENDPOINT" \
    --table-name "$DYNAMODB_TABLE_NAME" \
    --query "Table.GlobalSecondaryIndexes[?IndexName=='repository-runs'].IndexName | [0]" --output text)
  if [ "$index" != repository-runs ]; then
    aws dynamodb update-table --endpoint-url "$DYNAMODB_ENDPOINT" \
      --table-name "$DYNAMODB_TABLE_NAME" \
      --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=runSk,AttributeType=S \
      --global-secondary-index-updates \
        '[{"Create":{"IndexName":"repository-runs","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"runSk","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}}]'
  fi
fi

aws dynamodb wait table-exists --endpoint-url "$DYNAMODB_ENDPOINT" \
  --table-name "$DYNAMODB_TABLE_NAME"
attempt=0
while :; do
  index_status=$(aws dynamodb describe-table --endpoint-url "$DYNAMODB_ENDPOINT" \
    --table-name "$DYNAMODB_TABLE_NAME" \
    --query "Table.GlobalSecondaryIndexes[?IndexName=='repository-runs'].IndexStatus | [0]" --output text)
  if [ "$index_status" = ACTIVE ]; then
    break
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "DynamoDB Local repository-runs index did not become active" >&2
    exit 1
  fi
  sleep 2
done
aws dynamodb describe-table --endpoint-url "$DYNAMODB_ENDPOINT" \
  --table-name "$DYNAMODB_TABLE_NAME" \
  --query 'Table.{Status:TableStatus,Indexes:GlobalSecondaryIndexes[].IndexName}'
