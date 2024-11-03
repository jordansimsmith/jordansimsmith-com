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

  subscriptions = ["jordansimsmith@gmail.com"]
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

resource "aws_dynamodb_table" "price_tracker" {
  name         = "price_tracker"
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

  deletion_protection_enabled = true

  tags = local.tags
}

data "aws_iam_policy_document" "lambda_dynamodb" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.price_tracker.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      "dynamodb:ConditionCheckItem",
    ]
  }
}

resource "aws_iam_policy" "lambda_dynamodb" {
  name   = "${local.application_id}_lambda_dynamodb"
  policy = data.aws_iam_policy_document.lambda_dynamodb.json
  tags   = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}

resource "aws_sns_topic" "price_updates" {
  name = "${local.application_id}_price_updates"
}

resource "aws_sns_topic_subscription" "price_updates" {
  for_each  = toset(local.subscriptions)
  topic_arn = aws_sns_topic.price_updates.arn
  protocol  = "email"
  endpoint  = each.value
}

data "aws_iam_policy_document" "lambda_sns" {
  statement {
    effect = "Allow"

    resources = [
      aws_sns_topic.price_updates.arn,
    ]

    actions = [
      "SNS:Publish",
      "SNS:GetTopicAttributes",
    ]
  }

  statement {
    effect = "Allow"

    resources = [
      "*"
    ]

    actions = [
      "SNS:ListTopics"
    ]
  }
}

resource "aws_iam_policy" "lambda_sns" {
  name   = "${local.application_id}_lambda_sns"
  policy = data.aws_iam_policy_document.lambda_sns.json
  tags   = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_sns" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_sns.arn
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
  memory_size      = 1024
  timeout          = 30
  tags             = local.tags
}

resource "aws_cloudwatch_event_rule" "trigger" {
  name                = "${local.application_id}_trigger"
  schedule_expression = "rate(1 hour)"
}

resource "aws_cloudwatch_event_target" "trigger" {
  rule      = aws_cloudwatch_event_rule.trigger.name
  target_id = "lambda"
  arn       = aws_lambda_function.lambda["update_prices"].arn
}

resource "aws_lambda_permission" "cloudwatch_trigger" {
  statement_id  = "AllowExecutionFromCloudWatch"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda["update_prices"].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.trigger.arn
}