terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "platform/infra/terraform.tfstate"
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
  functions = [
    "price_tracker_api_update_prices",
    "football_calendar_api_update_fixtures",
    "event_calendar_api_update_events",
    "subfootball_tracker_api_update_page_content"
  ]

  subscriptions = ["jordansimsmith@gmail.com"]
}

resource "aws_sns_topic" "lambda_failure_notifications" {
  name = "lambda_failure_notifications"
}

data "aws_iam_policy_document" "lambda_failure_notifications_policy" {
  statement {
    effect = "Allow"

    principals {
      identifiers = ["cloudwatch.amazonaws.com"]
      type        = "Service"
    }

    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.lambda_failure_notifications.arn]
  }
}

resource "aws_sns_topic_policy" "lambda_failure_notifications" {
  arn    = aws_sns_topic.lambda_failure_notifications.arn
  policy = data.aws_iam_policy_document.lambda_failure_notifications_policy.json
}

resource "aws_sns_topic_subscription" "lambda_failure_email" {
  for_each = toset(local.subscriptions)

  topic_arn = aws_sns_topic.lambda_failure_notifications.arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_cloudwatch_metric_alarm" "lambda_failures" {
  for_each = toset(local.functions)

  alarm_name          = "${each.value}_failure_alarm"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 3600
  statistic           = "Sum"
  threshold           = 1
  alarm_description   = "This alarm monitors for failures in the ${each.value} lambda function"
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = each.value
  }

  alarm_actions = [aws_sns_topic.lambda_failure_notifications.arn]
  ok_actions    = [aws_sns_topic.lambda_failure_notifications.arn]
}
