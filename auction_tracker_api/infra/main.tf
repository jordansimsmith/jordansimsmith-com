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

provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      application_id = local.application_id
    }
  }
}

locals {
  application_id = "auction_tracker_api"

  lambdas = {
    update_items_handler = {
      target  = "//auction_tracker_api:update-items-handler_deploy.jar"
      handler = "com.jordansimsmith.auctiontracker.UpdateItemsHandler"
    }
    send_digest_handler = {
      target  = "//auction_tracker_api:send-digest-handler_deploy.jar"
      handler = "com.jordansimsmith.auctiontracker.SendDigestHandler"
    }
  }

  subscriptions = ["jordansimsmith@gmail.com"]
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

resource "aws_sns_topic" "auction_tracker_digest" {
  name = "auction_tracker_api_digest"
}

resource "aws_sns_topic_subscription" "auction_tracker_digest" {
  for_each  = toset(local.subscriptions)
  topic_arn = aws_sns_topic.auction_tracker_digest.arn
  protocol  = "email"
  endpoint  = each.value
}

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "lambda_role" {
  name               = "${local.application_id}_lambda_role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

data "aws_iam_policy_document" "lambda_permissions_policy_document" {
  statement {
    sid    = "DynamoDBFullAccess"
    effect = "Allow"

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

    resources = [
      aws_dynamodb_table.auction_tracker_table.arn,
      "${aws_dynamodb_table.auction_tracker_table.arn}/index/*"
    ]
  }

  statement {
    sid    = "SNSPublishAccess"
    effect = "Allow"

    actions = [
      "sns:Publish"
    ]

    resources = [
      aws_sns_topic.auction_tracker_digest.arn
    ]
  }

  statement {
    sid    = "SNSListTopicsAccess"
    effect = "Allow"

    actions = [
      "sns:ListTopics"
    ]

    resources = ["*"]
  }
}

resource "aws_iam_policy" "lambda_permissions" {
  name   = "${local.application_id}_lambda_permissions"
  policy = data.aws_iam_policy_document.lambda_permissions_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_permissions" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_permissions.arn
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

resource "aws_lambda_function" "lambda" {
  for_each = local.lambdas

  filename         = data.external.handler_location[each.key].result.location
  function_name    = "${local.application_id}_${each.key}"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = filebase64sha256(data.external.handler_location[each.key].result.location)
  handler          = each.value.handler
  runtime          = "java17"
  memory_size      = 1024
  timeout          = 30
  architectures    = ["x86_64"]
}

resource "aws_cloudwatch_event_rule" "update_items" {
  name                = "${local.application_id}_update_items"
  description         = "Triggers the UpdateItemsHandler Lambda function"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "update_items_lambda" {
  rule      = aws_cloudwatch_event_rule.update_items.name
  target_id = "UpdateItemsHandler"
  arn       = aws_lambda_function.lambda["update_items_handler"].arn
}

resource "aws_lambda_permission" "allow_eventbridge_update_items" {
  statement_id  = "AllowEventBridgeInvokeUpdateItems"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda["update_items_handler"].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_items.arn
}

resource "aws_cloudwatch_event_rule" "send_digest" {
  name                = "${local.application_id}_send_digest"
  description         = "Triggers the SendDigestHandler Lambda function"
  schedule_expression = "cron(0 21 * * ? *)"
}

resource "aws_cloudwatch_event_target" "send_digest_lambda" {
  rule      = aws_cloudwatch_event_rule.send_digest.name
  target_id = "SendDigestHandler"
  arn       = aws_lambda_function.lambda["send_digest_handler"].arn
}

resource "aws_lambda_permission" "allow_eventbridge_send_digest" {
  statement_id  = "AllowEventBridgeInvokeSendDigest"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda["send_digest_handler"].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.send_digest.arn
}
