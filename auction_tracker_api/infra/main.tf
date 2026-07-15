terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "auction_tracker_api/infra/terraform.tfstate"
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
  application_id = "auction_tracker_api"
  subscriptions  = ["jordansimsmith@gmail.com"]
}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    update_items_handler = {
      handler  = "com.jordansimsmith.auctiontracker.UpdateItemsHandler"
      artifact = var.artifacts["update_items_handler"]
      timeout  = 300
    }
    send_digest_handler = {
      handler  = "com.jordansimsmith.auctiontracker.SendDigestHandler"
      artifact = var.artifacts["send_digest_handler"]
      timeout  = 30
    }
  }
}

resource "aws_dynamodb_table" "auction_tracker_table" {
  name         = "auction_tracker"
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

  global_secondary_index {
    name            = "gsi1"
    hash_key        = "gsi1pk"
    range_key       = "gsi1sk"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true

  tags = {
    Name = "auction_tracker"
  }
}

data "aws_iam_policy_document" "lambda_dynamodb" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.auction_tracker_table.arn,
      "${aws_dynamodb_table.auction_tracker_table.arn}/index/*"
    ]

    actions = [
      "dynamodb:BatchGetItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:ConditionCheckItem",
      "dynamodb:PutItem",
      "dynamodb:DescribeTable",
      "dynamodb:DeleteItem",
      "dynamodb:GetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      "dynamodb:UpdateItem"
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

resource "aws_secretsmanager_secret" "auction_tracker" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

data "aws_iam_policy_document" "lambda_secretsmanager" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.auction_tracker.arn
    ]

    actions = [
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds"
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

resource "aws_iam_role_policy_attachment" "lambda_secretsmanager" {
  role       = module.java_lambda.lambda_role_name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}

resource "aws_sns_topic" "auction_tracker_digest" {
  name = "auction_tracker_api_digest"
}

resource "aws_sns_topic_subscription" "auction_tracker_digest" {
  for_each  = toset(local.subscriptions)
  topic_arn = aws_sns_topic.auction_tracker_digest.arn
  protocol  = "email"
  endpoint  = each.value
}

data "aws_iam_policy_document" "lambda_sns" {
  statement {
    effect = "Allow"

    resources = [
      aws_sns_topic.auction_tracker_digest.arn
    ]

    actions = [
      "sns:Publish"
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["sns:ListTopics"]
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

resource "aws_cloudwatch_event_rule" "update_items" {
  name                = "${local.application_id}_update_items"
  description         = "Triggers the UpdateItemsHandler Lambda function"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "update_items_lambda" {
  rule      = aws_cloudwatch_event_rule.update_items.name
  target_id = "UpdateItemsHandler"
  arn       = module.java_lambda.lambda_functions["update_items_handler"].qualified_arn
}

resource "aws_lambda_permission" "allow_eventbridge_update_items" {
  statement_id  = "AllowEventBridgeInvokeUpdateItems"
  action        = "lambda:InvokeFunction"
  function_name = module.java_lambda.lambda_functions["update_items_handler"].function_name
  qualifier     = module.java_lambda.lambda_functions["update_items_handler"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_items.arn

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudwatch_event_rule" "send_digest" {
  name                = "${local.application_id}_send_digest"
  description         = "Triggers the SendDigestHandler Lambda function"
  schedule_expression = "cron(0 6 * * ? *)"
}

resource "aws_cloudwatch_event_target" "send_digest_lambda" {
  rule      = aws_cloudwatch_event_rule.send_digest.name
  target_id = "SendDigestHandler"
  arn       = module.java_lambda.lambda_functions["send_digest_handler"].qualified_arn
}

resource "aws_lambda_permission" "allow_eventbridge_send_digest" {
  statement_id  = "AllowEventBridgeInvokeSendDigest"
  action        = "lambda:InvokeFunction"
  function_name = module.java_lambda.lambda_functions["send_digest_handler"].function_name
  qualifier     = module.java_lambda.lambda_functions["send_digest_handler"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.send_digest.arn

  lifecycle {
    create_before_destroy = true
  }
}
