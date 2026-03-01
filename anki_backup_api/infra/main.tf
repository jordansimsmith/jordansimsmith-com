terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "anki_backup_api/infra/terraform.tfstate"
    region = "ap-southeast-2"
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.61"
    }
  }

  required_version = ">= 1.9.0"
}

provider "aws" {
  region = "ap-southeast-2"

  default_tags {
    tags = {
      application_id = local.application_id
    }
  }
}

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      application_id = local.application_id
    }
  }
}

variable "artifacts" {
  type = map(string)
}

locals {
  application_id = "anki_backup_api"

  lambdas = {
    auth = {
      target  = "//anki_backup_api:auth-handler_deploy.jar"
      handler = "com.jordansimsmith.ankibackup.AuthHandler"
    }
    create_backup = {
      target  = "//anki_backup_api:create-backup-handler_deploy.jar"
      handler = "com.jordansimsmith.ankibackup.CreateBackupHandler"
    }
    update_backup = {
      target  = "//anki_backup_api:update-backup-handler_deploy.jar"
      handler = "com.jordansimsmith.ankibackup.UpdateBackupHandler"
    }
    find_backups = {
      target  = "//anki_backup_api:find-backups-handler_deploy.jar"
      handler = "com.jordansimsmith.ankibackup.FindBackupsHandler"
    }
    get_backup = {
      target  = "//anki_backup_api:get-backup-handler_deploy.jar"
      handler = "com.jordansimsmith.ankibackup.GetBackupHandler"
    }
  }

  root_resources = {
    backups = { path = "backups" }
  }

  child_resources = {
    backup = { path = "{backup_id}", parent = "backups" }
  }

  all_resources = merge(local.root_resources, local.child_resources)

  endpoints = {
    create_backup = { resource = "backups", method = "POST", lambda = "create_backup" }
    find_backups  = { resource = "backups", method = "GET", lambda = "find_backups" }
    update_backup = { resource = "backup", method = "PUT", lambda = "update_backup" }
    get_backup    = { resource = "backup", method = "GET", lambda = "get_backup" }
  }

  all_resource_ids = merge(
    { for k, v in aws_api_gateway_resource.root_resource : k => v.id },
    { for k, v in aws_api_gateway_resource.child_resource : k => v.id }
  )
}

check "unique_resource_paths" {
  assert {
    condition     = length(local.all_resources) == length(distinct([for r in local.all_resources : r.path]))
    error_message = "Resource paths must be unique"
  }
}

check "valid_endpoint_resources" {
  assert {
    condition     = alltrue([for e in local.endpoints : contains(keys(local.all_resources), e.resource)])
    error_message = "All endpoint resources must reference a valid resource key"
  }
}

check "valid_child_resource_parents" {
  assert {
    condition     = alltrue([for r in local.child_resources : contains(keys(local.root_resources), r.parent)])
    error_message = "All child resource parents must reference a valid root resource key"
  }
}

check "valid_endpoint_lambdas" {
  assert {
    condition     = alltrue([for e in local.endpoints : contains(keys(local.lambdas), e.lambda)])
    error_message = "All endpoint lambdas must reference a valid lambda key"
  }
}

resource "aws_s3_bucket" "anki_backup" {
  bucket = "anki-backup.jordansimsmith.com"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  rule {
    id     = "expire-backups"
    status = "Enabled"

    expiration {
      days = 90
    }
  }

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_public_access_block" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "anki_backup" {
  name         = "anki_backup"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true
}

resource "aws_secretsmanager_secret" "anki_backup" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

data "aws_iam_policy_document" "lambda_sts_allow_policy_document" {
  statement {
    effect = "Allow"

    principals {
      identifiers = ["lambda.amazonaws.com"]
      type        = "Service"
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "lambda_role" {
  name               = "${local.application_id}_lambda_exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_sts_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.anki_backup.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:GetItem",
      "dynamodb:Query",
    ]
  }
}

resource "aws_iam_policy" "lambda_dynamodb" {
  name   = "${local.application_id}_lambda_dynamodb"
  policy = data.aws_iam_policy_document.lambda_dynamodb_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.anki_backup.arn
    ]

    actions = [
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds"
    ]
  }

  statement {
    effect = "Allow"

    resources = [
      "*"
    ]

    actions = [
      "secretsmanager:ListSecrets"
    ]
  }
}

resource "aws_iam_policy" "lambda_secretsmanager" {
  name   = "${local.application_id}_lambda_secretsmanager"
  policy = data.aws_iam_policy_document.lambda_secretsmanager_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_secretsmanager" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}

data "aws_iam_policy_document" "lambda_s3_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      "${aws_s3_bucket.anki_backup.arn}/*"
    ]

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
  }
}

resource "aws_iam_policy" "lambda_s3" {
  name   = "${local.application_id}_lambda_s3"
  policy = data.aws_iam_policy_document.lambda_s3_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_s3" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_s3.arn
}

resource "aws_lambda_function" "lambda" {
  for_each = local.lambdas

  filename         = var.artifacts[each.key]
  function_name    = "${local.application_id}_${each.key}"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = filebase64sha256(var.artifacts[each.key])
  handler          = each.value.handler
  runtime          = "java21"
  memory_size      = 512
  timeout          = 30
  architectures    = ["x86_64"]
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = local.lambdas

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda[each.key].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.anki_backup.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "anki_backup" {
  name = "${local.application_id}_gateway"
}

resource "aws_api_gateway_authorizer" "anki_backup" {
  name                             = "${local.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.anki_backup.id
  authorizer_uri                   = aws_lambda_function.lambda["auth"].invoke_arn
  type                             = "REQUEST"
  identity_source                  = "method.request.header.Authorization"
  authorizer_result_ttl_in_seconds = 0
}

resource "aws_api_gateway_gateway_response" "unauthorized" {
  rest_api_id   = aws_api_gateway_rest_api.anki_backup.id
  status_code   = "401"
  response_type = "UNAUTHORIZED"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = {
    "gatewayresponse.header.WWW-Authenticate" = "'Basic'"
  }
}

resource "aws_api_gateway_resource" "root_resource" {
  for_each = local.root_resources

  rest_api_id = aws_api_gateway_rest_api.anki_backup.id
  parent_id   = aws_api_gateway_rest_api.anki_backup.root_resource_id
  path_part   = each.value.path
}

resource "aws_api_gateway_resource" "child_resource" {
  for_each = local.child_resources

  rest_api_id = aws_api_gateway_rest_api.anki_backup.id
  parent_id   = aws_api_gateway_resource.root_resource[each.value.parent].id
  path_part   = each.value.path
}

resource "aws_api_gateway_method" "method" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.anki_backup.id
  resource_id   = local.all_resource_ids[each.value.resource]
  http_method   = each.value.method
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.anki_backup.id
}

resource "aws_api_gateway_integration" "integration" {
  for_each = local.endpoints

  rest_api_id             = aws_api_gateway_rest_api.anki_backup.id
  resource_id             = local.all_resource_ids[each.value.resource]
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.lambda[each.value.lambda].invoke_arn
}

resource "aws_api_gateway_deployment" "anki_backup" {
  rest_api_id = aws_api_gateway_rest_api.anki_backup.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.anki_backup,
      aws_api_gateway_gateway_response.unauthorized,
      aws_api_gateway_resource.root_resource,
      aws_api_gateway_resource.child_resource,
      aws_api_gateway_method.method,
      aws_api_gateway_integration.integration,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  deployment_id = aws_api_gateway_deployment.anki_backup.id
  rest_api_id   = aws_api_gateway_rest_api.anki_backup.id
  stage_name    = "prod"
}

resource "aws_acm_certificate" "anki_backup" {
  provider          = aws.us_east_1
  domain_name       = "api.anki-backup.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_domain_name" "anki_backup" {
  domain_name     = aws_acm_certificate.anki_backup.domain_name
  certificate_arn = aws_acm_certificate.anki_backup.arn
}

resource "aws_api_gateway_base_path_mapping" "anki_backup" {
  api_id      = aws_api_gateway_rest_api.anki_backup.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  domain_name = aws_api_gateway_domain_name.anki_backup.domain_name
}
