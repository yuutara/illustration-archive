#!/bin/sh
set -eu

if ! awslocal s3api head-bucket --bucket "$S3_BUCKET" >/dev/null 2>&1; then
  awslocal s3api create-bucket --bucket "$S3_BUCKET"
fi
