terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "subfootball_tracker_api/infra/terraform.tfstate"
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
  application_id = "subfootball_tracker_api"
  subscriptions  = ["jordansimsmith@gmail.com"]
}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    update_page_content = {
      handler  = "com.jordansimsmith.subfootballtracker.UpdatePageContentHandler"
      artifact = var.artifacts["update_page_content"]
      timeout  = 30
    }
  }
}

resource "aws_dynamodb_table" "subfootball_tracker" {
  name         = "subfootball_tracker"
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
      aws_dynamodb_table.subfootball_tracker.arn
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

resource "aws_sns_topic" "page_content_updates" {
  name = "${local.application_id}_page_content_updates"
}

resource "aws_sns_topic_subscription" "page_content_updates" {
  for_each  = toset(local.subscriptions)
  topic_arn = aws_sns_topic.page_content_updates.arn
  protocol  = "email"
  endpoint  = each.value
}

data "aws_iam_policy_document" "lambda_sns" {
  statement {
    effect = "Allow"

    resources = [
      aws_sns_topic.page_content_updates.arn,
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

resource "aws_cloudwatch_event_rule" "update_page_content" {
  name                = "${local.application_id}_update_page_content"
  description         = "Triggers the UpdatePageContentHandler Lambda function"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "trigger" {
  rule      = aws_cloudwatch_event_rule.update_page_content.name
  target_id = "lambda"
  arn       = module.java_lambda.lambda_functions["update_page_content"].qualified_arn
}

resource "aws_lambda_permission" "cloudwatch_trigger" {
  statement_id  = "AllowExecutionFromCloudWatch"
  action        = "lambda:InvokeFunction"
  function_name = module.java_lambda.lambda_functions["update_page_content"].function_name
  qualifier     = module.java_lambda.lambda_functions["update_page_content"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_page_content.arn

  lifecycle {
    create_before_destroy = true
  }
}
