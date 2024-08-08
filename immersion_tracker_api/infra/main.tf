terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "immersion_tracker_api/infra/terraform.tfstate"
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
}

locals {
  application_id = "immersion_tracker_api"
  tags = {
    application_id = local.application_id
  }
}

resource "aws_dynamodb_table" "immersion_tracker_table" {
  name         = "immersion_tracker"
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

  point_in_time_recovery {
    enabled = true
  }

  tags = local.tags
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

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.immersion_tracker_table.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      "dynamodb:ConditionCheckItem",
    ]
  }
}

resource "aws_iam_policy" "lambda_dynamodb_allow_policy_document" {
  name   = "${local.application_id}_lambda_dynamodb_allow"
  policy = data.aws_iam_policy_document.lambda_dynamodb_allow_policy_document.json
  tags   = local.tags
}

resource "aws_iam_role" "lambda_role" {
  name               = "${local.application_id}_lambda_exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_sts_allow_policy_document.json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_policy_attachment" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_dynamodb_allow_policy_document.arn
}

data "external" "get_progress_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:get-progress-handler_deploy.jar"
  }
}

data "local_file" "get_progress_handler_file" {
  filename = data.external.get_progress_handler_location.result.location
}

resource "aws_lambda_function" "get_progress_handler_lambda" {
  filename         = data.local_file.get_progress_handler_file.filename
  function_name    = "${local.application_id}_get_progress_handler"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.get_progress_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.GetProgressHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

