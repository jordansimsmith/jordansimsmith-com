terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "event_calendar_api/infra/terraform.tfstate"
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
  application_id = "event_calendar_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.event-calendar.jordansimsmith.com"
  authorization  = "NONE"

  lambdas = {
    get_calendar_subscription = {
      handler  = "com.jordansimsmith.eventcalendar.GetCalendarSubscriptionHandler"
      artifact = var.artifacts["get_calendar_subscription"]
      timeout  = 30
    }
    update_events = {
      handler  = "com.jordansimsmith.eventcalendar.UpdateEventsHandler"
      artifact = var.artifacts["update_events"]
      timeout  = 120
    }
  }

  endpoints = {
    get_calendar_subscription = { path = "calendar", method = "GET", lambda = "get_calendar_subscription" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_dynamodb_table" "event_calendar_table" {
  name         = "event_calendar"
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
      aws_dynamodb_table.event_calendar_table.arn
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

resource "aws_cloudwatch_event_rule" "update_events" {
  name                = "${local.application_id}_update_events"
  description         = "Triggers the UpdateEventsHandler Lambda function"
  schedule_expression = "rate(15 minutes)"
}

resource "aws_cloudwatch_event_target" "update_events_lambda" {
  rule      = aws_cloudwatch_event_rule.update_events.name
  target_id = "UpdateEventsHandler"
  arn       = module.java_api.lambda_functions["update_events"].qualified_arn
}

resource "aws_lambda_permission" "allow_eventbridge" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.java_api.lambda_functions["update_events"].function_name
  qualifier     = module.java_api.lambda_functions["update_events"].version
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_events.arn

  lifecycle {
    create_before_destroy = true
  }
}
