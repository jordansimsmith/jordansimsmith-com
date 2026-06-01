terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "football_calendar_api/infra/terraform.tfstate"
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
  application_id = "football_calendar_api"
  subscriptions  = ["jordansimsmith@gmail.com"]
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.football-calendar.jordansimsmith.com"
  authorization  = "NONE"

  lambdas = {
    get_calendar_subscription = {
      handler  = "com.jordansimsmith.footballcalendar.GetCalendarSubscriptionHandler"
      artifact = var.artifacts["get_calendar_subscription"]
      timeout  = 30
    }
    update_fixtures = {
      handler  = "com.jordansimsmith.footballcalendar.UpdateFixturesHandler"
      artifact = var.artifacts["update_fixtures"]
      timeout  = 30
    }
  }

  endpoints = {
    get_calendar_subscription = { path = "calendar", method = "GET", lambda = "get_calendar_subscription" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_dynamodb_table" "football_calendar_table" {
  name         = "football_calendar"
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

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.football_calendar_table.arn
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
      "dynamodb:DeleteItem",
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
  policy = data.aws_iam_policy_document.lambda_dynamodb_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}

resource "aws_sns_topic" "fixture_updates" {
  name = "${local.application_id}_fixture_updates"
}

resource "aws_sns_topic_subscription" "fixture_updates" {
  for_each  = toset(local.subscriptions)
  topic_arn = aws_sns_topic.fixture_updates.arn
  protocol  = "email"
  endpoint  = each.value
}

data "aws_iam_policy_document" "lambda_sns" {
  statement {
    effect = "Allow"

    resources = [
      aws_sns_topic.fixture_updates.arn,
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
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_sns.arn
}

resource "aws_cloudwatch_event_rule" "update_fixtures" {
  name                = "${local.application_id}_update_fixtures"
  description         = "Triggers the UpdateFixturesHandler Lambda function"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "update_fixtures_lambda" {
  rule      = aws_cloudwatch_event_rule.update_fixtures.name
  target_id = "UpdateFixturesHandler"
  arn       = module.java_api.lambda_functions["update_fixtures"].qualified_arn
}

resource "aws_lambda_permission" "allow_eventbridge" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.java_api.lambda_functions["update_fixtures"].function_name
  qualifier     = module.java_api.lambda_functions["update_fixtures"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_fixtures.arn

  lifecycle {
    create_before_destroy = true
  }
}
