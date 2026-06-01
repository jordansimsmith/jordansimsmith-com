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
  application_id = "price_tracker_api"
  subscriptions  = ["jordansimsmith@gmail.com"]
}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    update_prices = {
      handler  = "com.jordansimsmith.pricetracker.UpdatePricesHandler"
      artifact = var.artifacts["update_prices"]
      timeout  = 120
    }
  }
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
  role       = module.java_lambda.lambda_role_name
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
}

resource "aws_iam_role_policy_attachment" "lambda_sns" {
  role       = module.java_lambda.lambda_role_name
  policy_arn = aws_iam_policy.lambda_sns.arn
}

resource "aws_cloudwatch_event_rule" "update_prices" {
  name                = "${local.application_id}_update_prices"
  description         = "Triggers the UpdatePricesHandler Lambda function"
  schedule_expression = "rate(1 hour)"
}

resource "aws_cloudwatch_event_target" "trigger" {
  rule      = aws_cloudwatch_event_rule.update_prices.name
  target_id = "lambda"
  arn       = module.java_lambda.lambda_functions["update_prices"].qualified_arn
}

resource "aws_lambda_permission" "cloudwatch_trigger" {
  statement_id  = "AllowExecutionFromCloudWatch"
  action        = "lambda:InvokeFunction"
  function_name = module.java_lambda.lambda_functions["update_prices"].function_name
  qualifier     = module.java_lambda.lambda_functions["update_prices"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_prices.arn

  lifecycle {
    create_before_destroy = true
  }
}
