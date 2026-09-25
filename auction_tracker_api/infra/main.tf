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
  search_ids = {
    ram_g_skill            = "ram-g-skill"
    ram_gskill             = "ram-gskill"
    ram_trident_z          = "ram-trident-z"
    mtg_bulk               = "mtg-bulk"
    mtg_collection         = "mtg-collection"
    mtg_assorted           = "mtg-assorted"
    mtg_clear_out          = "mtg-clear-out"
    mtg_clearout           = "mtg-clearout"
    mtg_lot                = "mtg-lot"
    mtg_one_dollar_reserve = "mtg-one-dollar-reserve"
  }
}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    jobs_handler = {
      handler  = "com.jordansimsmith.auctiontracker.JobsHandler"
      artifact = var.artifacts["jobs_handler"]
      timeout  = 300
    }
  }

  role_policy_arns = {
    dynamodb       = aws_iam_policy.lambda_dynamodb.arn
    secretsmanager = aws_iam_policy.lambda_secretsmanager.arn
    sns            = aws_iam_policy.lambda_sns.arn
    sqs            = aws_iam_policy.lambda_sqs.arn
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

  attribute {
    name = "gsi2pk"
    type = "S"
  }

  attribute {
    name = "gsi2sk"
    type = "S"
  }

  global_secondary_index {
    name            = "gsi1"
    hash_key        = "gsi1pk"
    range_key       = "gsi1sk"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "gsi2"
    hash_key        = "gsi2pk"
    range_key       = "gsi2sk"
    projection_type = "KEYS_ONLY"
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

resource "aws_sqs_queue" "jobs_dlq" {
  name                        = "auction_tracker_jobs_dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  message_retention_seconds   = 1209600
}

resource "aws_sqs_queue" "jobs" {
  name                        = "auction_tracker_jobs.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  message_retention_seconds   = 1209600
  visibility_timeout_seconds  = 1800

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dlq.arn
    maxReceiveCount     = 5
  })
}

data "aws_iam_policy_document" "lambda_sqs" {
  statement {
    effect = "Allow"

    resources = [aws_sqs_queue.jobs.arn]

    actions = [
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:ReceiveMessage",
      "sqs:ChangeMessageVisibility",
    ]
  }
}

resource "aws_iam_policy" "lambda_sqs" {
  name   = "${local.application_id}_lambda_sqs"
  policy = data.aws_iam_policy_document.lambda_sqs.json
}

resource "aws_lambda_event_source_mapping" "jobs" {
  event_source_arn                   = aws_sqs_queue.jobs.arn
  function_name                      = module.java_lambda.lambda_functions["jobs_handler"].qualified_arn
  batch_size                         = 1
  maximum_batching_window_in_seconds = 0
}

data "aws_iam_policy_document" "jobs_scheduler_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "jobs_scheduler" {
  name               = "${local.application_id}_jobs_scheduler"
  assume_role_policy = data.aws_iam_policy_document.jobs_scheduler_assume_role.json
}

data "aws_iam_policy_document" "jobs_scheduler_sqs" {
  statement {
    effect = "Allow"

    resources = [aws_sqs_queue.jobs.arn]

    actions = ["sqs:SendMessage"]
  }
}

resource "aws_iam_policy" "jobs_scheduler_sqs" {
  name   = "${local.application_id}_jobs_scheduler_sqs"
  policy = data.aws_iam_policy_document.jobs_scheduler_sqs.json
}

resource "aws_iam_role_policy_attachment" "jobs_scheduler_sqs" {
  role       = aws_iam_role.jobs_scheduler.name
  policy_arn = aws_iam_policy.jobs_scheduler_sqs.arn
}

resource "aws_scheduler_schedule" "update_search" {
  for_each                     = local.search_ids
  name                         = "${local.application_id}_update_${each.key}"
  description                  = "Queues the ${each.value} auction search"
  schedule_expression          = "cron(0/15 * * * ? *)"
  schedule_expression_timezone = "Pacific/Auckland"
  depends_on                   = [aws_iam_role_policy_attachment.jobs_scheduler_sqs]

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage"
    role_arn = aws_iam_role.jobs_scheduler.arn
    input    = <<-JSON
      {
        "QueueUrl": "${aws_sqs_queue.jobs.url}",
        "MessageBody": "{\"job_type\":\"update_search\",\"search_id\":\"${each.value}\",\"scheduled_at\":\"<aws.scheduler.scheduled-time>\"}",
        "MessageGroupId": "auction-tracker"
      }
    JSON

    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 5
    }

  }
}

resource "aws_scheduler_schedule" "send_digest" {
  name                         = "${local.application_id}_send_digest"
  description                  = "Queues the daily auction digest"
  schedule_expression          = "cron(5 21 * * ? *)"
  schedule_expression_timezone = "Pacific/Auckland"
  depends_on                   = [aws_iam_role_policy_attachment.jobs_scheduler_sqs]

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:sqs:sendMessage"
    role_arn = aws_iam_role.jobs_scheduler.arn
    input    = <<-JSON
      {
        "QueueUrl": "${aws_sqs_queue.jobs.url}",
        "MessageBody": "{\"job_type\":\"send_digest\",\"scheduled_at\":\"<aws.scheduler.scheduled-time>\"}",
        "MessageGroupId": "auction-tracker"
      }
    JSON

    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 5
    }

  }
}
