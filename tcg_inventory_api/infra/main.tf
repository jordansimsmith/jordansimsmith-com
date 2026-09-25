terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "tcg_inventory_api/infra/terraform.tfstate"
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

variable "images" {
  type = map(object({
    repository = string
    uri        = string
  }))
}

locals {
  application_id = "tcg_inventory_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id     = local.application_id
  domain_name        = "api.tcg-inventory.jordansimsmith.com"
  cors_origin        = "https://tcg-inventory.jordansimsmith.com"
  binary_media_types = ["image/jpeg"]

  lambdas = {
    get_settings = {
      handler  = "com.jordansimsmith.tcginventory.settings.GetSettingsHandler"
      artifact = var.artifacts["get_settings"]
    }
    update_settings = {
      handler  = "com.jordansimsmith.tcginventory.settings.UpdateSettingsHandler"
      artifact = var.artifacts["update_settings"]
    }
    create_import = {
      handler  = "com.jordansimsmith.tcginventory.imports.CreateImportHandler"
      artifact = var.artifacts["create_import"]
    }
    find_imports = {
      handler  = "com.jordansimsmith.tcginventory.imports.FindImportsHandler"
      artifact = var.artifacts["find_imports"]
    }
    get_import = {
      handler  = "com.jordansimsmith.tcginventory.imports.GetImportHandler"
      artifact = var.artifacts["get_import"]
    }
    delete_import = {
      handler  = "com.jordansimsmith.tcginventory.imports.DeleteImportHandler"
      artifact = var.artifacts["delete_import"]
    }
    confirm_import = {
      handler  = "com.jordansimsmith.tcginventory.imports.ConfirmImportHandler"
      artifact = var.artifacts["confirm_import"]
    }
    create_scan = {
      handler  = "com.jordansimsmith.tcginventory.scans.CreateScanHandler"
      artifact = var.artifacts["create_scan"]
    }
    find_scans = {
      handler  = "com.jordansimsmith.tcginventory.scans.FindScansHandler"
      artifact = var.artifacts["find_scans"]
    }
    get_scan = {
      handler  = "com.jordansimsmith.tcginventory.scans.GetScanHandler"
      artifact = var.artifacts["get_scan"]
    }
    identify_scan = {
      handler  = "com.jordansimsmith.tcginventory.scans.IdentifyScanHandler"
      artifact = var.artifacts["identify_scan"]
    }
    confirm_scan = {
      handler  = "com.jordansimsmith.tcginventory.scans.ConfirmScanHandler"
      artifact = var.artifacts["confirm_scan"]
    }
    delete_scan_row = {
      handler  = "com.jordansimsmith.tcginventory.scans.DeleteScanRowHandler"
      artifact = var.artifacts["delete_scan_row"]
    }
    delete_scan = {
      handler  = "com.jordansimsmith.tcginventory.scans.DeleteScanHandler"
      artifact = var.artifacts["delete_scan"]
    }
    update_import_row = {
      handler  = "com.jordansimsmith.tcginventory.imports.UpdateImportRowHandler"
      artifact = var.artifacts["update_import_row"]
    }
    delete_import_row = {
      handler  = "com.jordansimsmith.tcginventory.imports.DeleteImportRowHandler"
      artifact = var.artifacts["delete_import_row"]
    }
    create_import_row_photo = {
      handler  = "com.jordansimsmith.tcginventory.imports.CreateImportRowPhotoHandler"
      artifact = var.artifacts["create_import_row_photo"]
    }
    delete_import_row_photo = {
      handler  = "com.jordansimsmith.tcginventory.imports.DeleteImportRowPhotoHandler"
      artifact = var.artifacts["delete_import_row_photo"]
    }
    create_publish = {
      handler  = "com.jordansimsmith.tcginventory.publish.CreatePublishHandler"
      artifact = var.artifacts["create_publish"]
    }
    get_publish = {
      handler  = "com.jordansimsmith.tcginventory.publish.GetPublishHandler"
      artifact = var.artifacts["get_publish"]
    }
    create_report = {
      handler  = "com.jordansimsmith.tcginventory.reports.CreateReportHandler"
      artifact = var.artifacts["create_report"]
    }
    get_reports = {
      handler  = "com.jordansimsmith.tcginventory.reports.GetReportsHandler"
      artifact = var.artifacts["get_reports"]
    }
    find_skus = {
      handler  = "com.jordansimsmith.tcginventory.inventory.FindSkusHandler"
      artifact = var.artifacts["find_skus"]
    }
    get_sku = {
      handler  = "com.jordansimsmith.tcginventory.inventory.GetSkuHandler"
      artifact = var.artifacts["get_sku"]
    }
    remove_unit = {
      handler  = "com.jordansimsmith.tcginventory.inventory.RemoveUnitHandler"
      artifact = var.artifacts["remove_unit"]
    }
    update_unit = {
      handler  = "com.jordansimsmith.tcginventory.inventory.UpdateUnitHandler"
      artifact = var.artifacts["update_unit"]
    }
    find_orders = {
      handler  = "com.jordansimsmith.tcginventory.orders.FindOrdersHandler"
      artifact = var.artifacts["find_orders"]
    }
    get_order = {
      handler  = "com.jordansimsmith.tcginventory.orders.GetOrderHandler"
      artifact = var.artifacts["get_order"]
    }
    confirm_order = {
      handler  = "com.jordansimsmith.tcginventory.orders.ConfirmOrderHandler"
      artifact = var.artifacts["confirm_order"]
    }
    jobs_handler = {
      handler  = "com.jordansimsmith.tcginventory.JobsHandler"
      artifact = var.artifacts["jobs_handler"]
      timeout  = 900
    }
  }

  endpoints = {
    get_settings            = { path = "settings", method = "GET", lambda = "get_settings" }
    update_settings         = { path = "settings", method = "PATCH", lambda = "update_settings" }
    create_import           = { path = "imports", method = "POST", lambda = "create_import" }
    find_imports            = { path = "imports", method = "GET", lambda = "find_imports" }
    get_import              = { path = "imports/{import_id}", method = "GET", lambda = "get_import" }
    delete_import           = { path = "imports/{import_id}", method = "DELETE", lambda = "delete_import" }
    confirm_import          = { path = "imports/{import_id}/confirm", method = "POST", lambda = "confirm_import" }
    create_scan             = { path = "scans", method = "POST", lambda = "create_scan" }
    find_scans              = { path = "scans", method = "GET", lambda = "find_scans" }
    get_scan                = { path = "scans/{scan_id}", method = "GET", lambda = "get_scan" }
    identify_scan           = { path = "scans/{scan_id}/identify", method = "POST", lambda = "identify_scan" }
    confirm_scan            = { path = "scans/{scan_id}/confirm", method = "POST", lambda = "confirm_scan" }
    delete_scan_row         = { path = "scans/{scan_id}/rows/{scan_position}", method = "DELETE", lambda = "delete_scan_row" }
    delete_scan             = { path = "scans/{scan_id}", method = "DELETE", lambda = "delete_scan" }
    update_import_row       = { path = "imports/{import_id}/rows/{position}", method = "PUT", lambda = "update_import_row" }
    delete_import_row       = { path = "imports/{import_id}/rows/{position}", method = "DELETE", lambda = "delete_import_row" }
    create_import_row_photo = { path = "imports/{import_id}/rows/{position}/photos", method = "POST", lambda = "create_import_row_photo" }
    delete_import_row_photo = { path = "imports/{import_id}/rows/{position}/photos/{photo_id}", method = "DELETE", lambda = "delete_import_row_photo" }
    create_publish          = { path = "publish", method = "POST", lambda = "create_publish" }
    get_publish             = { path = "publish", method = "GET", lambda = "get_publish" }
    create_report           = { path = "reports", method = "POST", lambda = "create_report" }
    get_reports             = { path = "reports", method = "GET", lambda = "get_reports" }
    find_skus               = { path = "skus", method = "GET", lambda = "find_skus" }
    get_sku                 = { path = "skus/{sku_id}", method = "GET", lambda = "get_sku" }
    remove_unit             = { path = "skus/{sku_id}/units/{sequence_number}", method = "DELETE", lambda = "remove_unit" }
    update_unit             = { path = "skus/{sku_id}/units/{sequence_number}", method = "PUT", lambda = "update_unit" }
    find_orders             = { path = "orders", method = "GET", lambda = "find_orders" }
    get_order               = { path = "orders/{order_id}", method = "GET", lambda = "get_order" }
    confirm_order           = { path = "orders/{order_id}/confirm", method = "POST", lambda = "confirm_order" }
  }

  role_policy_arns = {
    dynamodb       = aws_iam_policy.lambda_dynamodb.arn
    sqs            = aws_iam_policy.lambda_sqs.arn
    secretsmanager = aws_iam_policy.lambda_secretsmanager.arn
    s3             = aws_iam_policy.lambda_s3.arn
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }

  # snapstart snapshot init resolves the jobs queue, dynamodb table, and s3
  # bucket at startup, so they must exist before any lambda version is published
  depends_on = [
    aws_sqs_queue.jobs,
    aws_sqs_queue.scan_jobs,
    aws_dynamodb_table.tcg_inventory,
    aws_s3_bucket.tcg_inventory,
  ]
}

resource "aws_s3_bucket" "tcg_inventory" {
  bucket = "api.tcg-inventory.jordansimsmith.com"
}

resource "aws_s3_bucket_cors_configuration" "tcg_inventory" {
  bucket = aws_s3_bucket.tcg_inventory.id

  cors_rule {
    allowed_headers = ["Content-Type", "If-None-Match"]
    allowed_methods = ["GET", "PUT"]
    allowed_origins = ["https://tcg-inventory.jordansimsmith.com"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tcg_inventory" {
  bucket = aws_s3_bucket.tcg_inventory.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tcg_inventory" {
  bucket = aws_s3_bucket.tcg_inventory.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "tcg_inventory" {
  name         = "tcg_inventory"
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

  attribute {
    name = "gsi1pk"
    type = "S"
  }

  attribute {
    name = "gsi1sk"
    type = "S"
  }

  attribute {
    name = "gsi2pk"
    type = "S"
  }

  attribute {
    name = "gsi2sk"
    type = "S"
  }

  attribute {
    name = "gsi3pk"
    type = "S"
  }

  attribute {
    name = "sequence_number"
    type = "N"
  }

  global_secondary_index {
    name            = "gsi1"
    hash_key        = "gsi1pk"
    range_key       = "gsi1sk"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "gsi2"
    hash_key        = "gsi2pk"
    range_key       = "gsi2sk"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "gsi3"
    hash_key        = "gsi3pk"
    range_key       = "sequence_number"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true
}

# content-based deduplication is disabled because job slice messages are
# byte-identical; senders set an explicit deduplication id of
# <job_id>#<continuation> and a send missing one fails loudly
resource "aws_sqs_queue" "jobs_dlq" {
  name                        = "tcg_inventory_jobs_dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
}

resource "aws_sqs_queue" "jobs" {
  name                        = "tcg_inventory_jobs.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = 960

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_lambda_event_source_mapping" "jobs" {
  event_source_arn                   = aws_sqs_queue.jobs.arn
  function_name                      = module.java_api.lambda_functions["jobs_handler"].qualified_arn
  batch_size                         = 1
  maximum_batching_window_in_seconds = 0
}

resource "aws_sqs_queue" "scan_jobs_dlq" {
  name                        = "tcg_inventory_scan_jobs_dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
}

resource "aws_sqs_queue" "scan_jobs" {
  name                        = "tcg_inventory_scan_jobs.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = 960

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.scan_jobs_dlq.arn
    maxReceiveCount     = 5
  })
}

data "aws_iam_policy_document" "scan_worker_ecr" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
  }
}

resource "aws_ecr_repository_policy" "scan_worker" {
  repository = var.images["scan_worker"].repository
  policy     = data.aws_iam_policy_document.scan_worker_ecr.json
}

resource "aws_secretsmanager_secret" "tcg_inventory" {
  name                    = "tcg_inventory"
  recovery_window_in_days = 0
}

data "aws_iam_policy_document" "lambda_dynamodb" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.tcg_inventory.arn,
      "${aws_dynamodb_table.tcg_inventory.arn}/index/*"
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      "dynamodb:DeleteItem",
      "dynamodb:ConditionCheckItem",
      "dynamodb:TransactWriteItems",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["dynamodb:ListTables"]
  }
}

resource "aws_iam_policy" "lambda_dynamodb" {
  name   = "${local.application_id}_lambda_dynamodb"
  policy = data.aws_iam_policy_document.lambda_dynamodb.json
}

data "aws_iam_policy_document" "lambda_sqs" {
  statement {
    effect = "Allow"

    resources = [
      aws_sqs_queue.jobs.arn,
    ]

    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
    ]
  }

  statement {
    effect = "Allow"

    resources = [
      aws_sqs_queue.scan_jobs.arn,
    ]

    actions = [
      "sqs:SendMessage",
      "sqs:GetQueueUrl",
    ]
  }
}

resource "aws_iam_policy" "lambda_sqs" {
  name   = "${local.application_id}_lambda_sqs"
  policy = data.aws_iam_policy_document.lambda_sqs.json
}

data "aws_iam_policy_document" "lambda_secretsmanager" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.tcg_inventory.arn,
    ]

    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:PutSecretValue",
      "secretsmanager:DescribeSecret",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["secretsmanager:ListSecrets"]
  }
}

resource "aws_iam_policy" "lambda_secretsmanager" {
  name   = "${local.application_id}_lambda_secretsmanager"
  policy = data.aws_iam_policy_document.lambda_secretsmanager.json
}

data "aws_iam_policy_document" "lambda_s3" {
  statement {
    effect = "Allow"

    resources = [
      "${aws_s3_bucket.tcg_inventory.arn}/*"
    ]

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:DeleteObject",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["s3:ListAllMyBuckets"]
  }
}

resource "aws_iam_policy" "lambda_s3" {
  name   = "${local.application_id}_lambda_s3"
  policy = data.aws_iam_policy_document.lambda_s3.json
}

data "aws_iam_policy_document" "scan_worker" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.tcg_inventory.arn,
      "${aws_dynamodb_table.tcg_inventory.arn}/index/*",
    ]

    actions = [
      "dynamodb:GetItem",
      "dynamodb:Query",
      "dynamodb:TransactWriteItems",
      "dynamodb:UpdateItem",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["${aws_s3_bucket.tcg_inventory.arn}/users/*/scans/*"]
    actions   = ["s3:GetObject"]
  }

  statement {
    effect    = "Allow"
    resources = [aws_sqs_queue.scan_jobs.arn]
    actions = [
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
      "sqs:ReceiveMessage",
      "sqs:SendMessage",
    ]
  }
}

resource "aws_iam_policy" "scan_worker" {
  name   = "${local.application_id}_scan_worker"
  policy = data.aws_iam_policy_document.scan_worker.json
}

module "scan_worker" {
  source = "../../infra/modules/container_lambda"

  application_id = local.application_id
  name           = "scan_worker"
  image_uri      = var.images["scan_worker"].uri
  memory_size    = 1769
  timeout        = 900
  architectures  = ["x86_64"]

  environment = {
    SCAN_TABLE_NAME       = aws_dynamodb_table.tcg_inventory.name
    SCAN_BUCKET_NAME      = aws_s3_bucket.tcg_inventory.bucket
    COLLECTORVISION_CACHE = "/opt/collectorvision"
  }

  role_policy_arns = {
    scan_worker = aws_iam_policy.scan_worker.arn
  }

  event_source_mappings = {
    scan_jobs = {
      event_source_arn                   = aws_sqs_queue.scan_jobs.arn
      batch_size                         = 1
      maximum_batching_window_in_seconds = 0
    }
  }

  depends_on = [aws_ecr_repository_policy.scan_worker]
}

moved {
  from = aws_iam_role.scan_worker
  to   = module.scan_worker.aws_iam_role.lambda_role
}

moved {
  from = aws_iam_role_policy_attachment.scan_worker_basic
  to   = module.scan_worker.aws_iam_role_policy_attachment.lambda_basic
}

moved {
  from = aws_iam_role_policy_attachment.scan_worker
  to   = module.scan_worker.aws_iam_role_policy_attachment.lambda_custom["scan_worker"]
}

moved {
  from = aws_cloudwatch_log_group.scan_worker
  to   = module.scan_worker.aws_cloudwatch_log_group.lambda
}

moved {
  from = aws_lambda_function.scan_worker
  to   = module.scan_worker.aws_lambda_function.lambda
}

moved {
  from = aws_lambda_event_source_mapping.scan_jobs
  to   = module.scan_worker.aws_lambda_event_source_mapping.lambda["scan_jobs"]
}
