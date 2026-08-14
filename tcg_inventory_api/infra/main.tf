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

locals {
  application_id = "tcg_inventory_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.tcg-inventory.jordansimsmith.com"
  cors_origin    = "https://tcg-inventory.jordansimsmith.com"

  lambdas = {
    get_settings = {
      handler  = "com.jordansimsmith.tcginventory.GetSettingsHandler"
      artifact = var.artifacts["get_settings"]
    }
    put_settings = {
      handler  = "com.jordansimsmith.tcginventory.PutSettingsHandler"
      artifact = var.artifacts["put_settings"]
    }
    create_import = {
      handler  = "com.jordansimsmith.tcginventory.CreateImportHandler"
      artifact = var.artifacts["create_import"]
    }
    find_imports = {
      handler  = "com.jordansimsmith.tcginventory.FindImportsHandler"
      artifact = var.artifacts["find_imports"]
    }
    get_import = {
      handler  = "com.jordansimsmith.tcginventory.GetImportHandler"
      artifact = var.artifacts["get_import"]
    }
    delete_import = {
      handler  = "com.jordansimsmith.tcginventory.DeleteImportHandler"
      artifact = var.artifacts["delete_import"]
    }
    confirm_import = {
      handler  = "com.jordansimsmith.tcginventory.ConfirmImportHandler"
      artifact = var.artifacts["confirm_import"]
    }
    update_import_row = {
      handler  = "com.jordansimsmith.tcginventory.UpdateImportRowHandler"
      artifact = var.artifacts["update_import_row"]
    }
    delete_import_row = {
      handler  = "com.jordansimsmith.tcginventory.DeleteImportRowHandler"
      artifact = var.artifacts["delete_import_row"]
    }
    create_publish = {
      handler  = "com.jordansimsmith.tcginventory.CreatePublishHandler"
      artifact = var.artifacts["create_publish"]
    }
    get_publish = {
      handler  = "com.jordansimsmith.tcginventory.GetPublishHandler"
      artifact = var.artifacts["get_publish"]
    }
    find_skus = {
      handler  = "com.jordansimsmith.tcginventory.FindSkusHandler"
      artifact = var.artifacts["find_skus"]
    }
    get_sku = {
      handler  = "com.jordansimsmith.tcginventory.GetSkuHandler"
      artifact = var.artifacts["get_sku"]
    }
    remove_unit = {
      handler  = "com.jordansimsmith.tcginventory.RemoveUnitHandler"
      artifact = var.artifacts["remove_unit"]
    }
    update_unit = {
      handler  = "com.jordansimsmith.tcginventory.UpdateUnitHandler"
      artifact = var.artifacts["update_unit"]
    }
    find_orders = {
      handler  = "com.jordansimsmith.tcginventory.FindOrdersHandler"
      artifact = var.artifacts["find_orders"]
    }
    get_order = {
      handler  = "com.jordansimsmith.tcginventory.GetOrderHandler"
      artifact = var.artifacts["get_order"]
    }
    confirm_order = {
      handler  = "com.jordansimsmith.tcginventory.ConfirmOrderHandler"
      artifact = var.artifacts["confirm_order"]
    }
    jobs_handler = {
      handler  = "com.jordansimsmith.tcginventory.JobsHandler"
      artifact = var.artifacts["jobs_handler"]
      timeout  = 900
    }
  }

  endpoints = {
    get_settings      = { path = "settings", method = "GET", lambda = "get_settings" }
    put_settings      = { path = "settings", method = "PUT", lambda = "put_settings" }
    create_import     = { path = "imports", method = "POST", lambda = "create_import" }
    find_imports      = { path = "imports", method = "GET", lambda = "find_imports" }
    get_import        = { path = "imports/{import_id}", method = "GET", lambda = "get_import" }
    delete_import     = { path = "imports/{import_id}", method = "DELETE", lambda = "delete_import" }
    confirm_import    = { path = "imports/{import_id}/confirm", method = "POST", lambda = "confirm_import" }
    update_import_row = { path = "imports/{import_id}/rows/{position}", method = "PUT", lambda = "update_import_row" }
    delete_import_row = { path = "imports/{import_id}/rows/{position}", method = "DELETE", lambda = "delete_import_row" }
    create_publish    = { path = "publish", method = "POST", lambda = "create_publish" }
    get_publish       = { path = "publish", method = "GET", lambda = "get_publish" }
    find_skus         = { path = "skus", method = "GET", lambda = "find_skus" }
    get_sku           = { path = "skus/{sku_id}", method = "GET", lambda = "get_sku" }
    remove_unit       = { path = "skus/{sku_id}/units/{sequence_number}", method = "DELETE", lambda = "remove_unit" }
    update_unit       = { path = "skus/{sku_id}/units/{sequence_number}", method = "PUT", lambda = "update_unit" }
    find_orders       = { path = "orders", method = "GET", lambda = "find_orders" }
    get_order         = { path = "orders/{order_id}", method = "GET", lambda = "get_order" }
    confirm_order     = { path = "orders/{order_id}/confirm", method = "POST", lambda = "confirm_order" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
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

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true
}

resource "aws_sqs_queue" "jobs_dlq" {
  name                        = "${local.application_id}_jobs_dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
}

resource "aws_sqs_queue" "jobs" {
  name                        = "${local.application_id}_jobs.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
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

resource "aws_lambda_permission" "sqs_invoke_jobs_handler" {
  statement_id  = "AllowSQSInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.java_api.lambda_functions["jobs_handler"].function_name
  qualifier     = module.java_api.lambda_functions["jobs_handler"].version
  principal     = "sqs.amazonaws.com"
  source_arn    = aws_sqs_queue.jobs.arn

  lifecycle {
    create_before_destroy = true
  }
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

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}

data "aws_iam_policy_document" "lambda_sqs" {
  statement {
    effect = "Allow"

    resources = [
      aws_sqs_queue.jobs.arn,
      aws_sqs_queue.jobs_dlq.arn,
    ]

    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
    ]
  }
}

resource "aws_iam_policy" "lambda_sqs" {
  name   = "${local.application_id}_lambda_sqs"
  policy = data.aws_iam_policy_document.lambda_sqs.json
}

resource "aws_iam_role_policy_attachment" "lambda_sqs" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_sqs.arn
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
}

resource "aws_iam_policy" "lambda_secretsmanager" {
  name   = "${local.application_id}_lambda_secretsmanager"
  policy = data.aws_iam_policy_document.lambda_secretsmanager.json
}

resource "aws_iam_role_policy_attachment" "lambda_secretsmanager" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}
