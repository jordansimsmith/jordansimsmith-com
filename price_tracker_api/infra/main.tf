terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "price_tracker_api/infra/terraform.tfstate"
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
  application_id = "price_tracker_api"
  tags = {
    application_id = local.application_id
  }

  lambdas = {
    update_prices = {
      target  = "//price_tracker_api:update-prices-handler_deploy.jar"
      handler = "com.jordansimsmith.pricetracker.UpdatePricesHandler"
    }
  }
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
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "external" "handler_location" {
  for_each = local.lambdas

  program = ["bash", "../../tools/terraform/resolve_location.sh"]

  query = {
    target = each.value.target
  }
}

data "local_file" "handler_file" {
  for_each = local.lambdas

  filename = data.external.handler_location[each.key].result.location
}

resource "aws_lambda_function" "lambda" {
  for_each = local.lambdas

  filename         = data.local_file.handler_file[each.key].filename
  function_name    = "${local.application_id}_${each.key}"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.handler_file[each.key].content_base64sha256
  handler          = each.value.handler
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}
