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
  paused_product_schedules = toset([
    "sportsfuel_clean_nutrition",
    "vivobarefoot_tracker_forest",
  ])
  product_ids = {
    chemist_warehouse_98676     = "chemist-warehouse-98676"
    chemist_warehouse_74330     = "chemist-warehouse-74330"
    chemist_warehouse_74329     = "chemist-warehouse-74329"
    chemist_warehouse_98677     = "chemist-warehouse-98677"
    chemist_warehouse_79763     = "chemist-warehouse-79763"
    chemist_warehouse_79762     = "chemist-warehouse-79762"
    chemist_warehouse_74332     = "chemist-warehouse-74332"
    chemist_warehouse_74331     = "chemist-warehouse-74331"
    chemist_warehouse_74350     = "chemist-warehouse-74350"
    chemist_warehouse_74347     = "chemist-warehouse-74347"
    chemist_warehouse_74336     = "chemist-warehouse-74336"
    chemist_warehouse_91351     = "chemist-warehouse-91351"
    chemist_warehouse_111308    = "chemist-warehouse-111308"
    chemist_warehouse_111307    = "chemist-warehouse-111307"
    chemist_warehouse_111309    = "chemist-warehouse-111309"
    chemist_warehouse_111303    = "chemist-warehouse-111303"
    chemist_warehouse_111301    = "chemist-warehouse-111301"
    chemist_warehouse_111305    = "chemist-warehouse-111305"
    chemist_warehouse_111302    = "chemist-warehouse-111302"
    chemist_warehouse_111304    = "chemist-warehouse-111304"
    chemist_warehouse_111306    = "chemist-warehouse-111306"
    chemist_warehouse_120088    = "chemist-warehouse-120088"
    chemist_warehouse_101969    = "chemist-warehouse-101969"
    chemist_warehouse_80063     = "chemist-warehouse-80063"
    chemist_warehouse_82946     = "chemist-warehouse-82946"
    chemist_warehouse_136022    = "chemist-warehouse-136022"
    chemist_warehouse_88817     = "chemist-warehouse-88817"
    chemist_warehouse_80060     = "chemist-warehouse-80060"
    chemist_warehouse_82940     = "chemist-warehouse-82940"
    chemist_warehouse_80061     = "chemist-warehouse-80061"
    chemist_warehouse_136023    = "chemist-warehouse-136023"
    chemist_warehouse_63104     = "chemist-warehouse-63104"
    nz_protein_nz_whey          = "nz-protein-nz-whey"
    sportsfuel_clean_nutrition  = "sportsfuel-clean-nutrition"
    vivobarefoot_tracker_forest = "vivo-tracker-forest"
  }
  product_message_group_prefixes = {
    "chemist-warehouse-" = "chemist-warehouse"
    "nz-protein-"        = "nz-protein"
    "sportsfuel-"        = "sportsfuel"
    "vivo-"              = "vivobarefoot"
  }
  product_message_group_ids = {
    for schedule_name, product_id in local.product_ids :
    schedule_name => one([
      for prefix, message_group_id in local.product_message_group_prefixes :
      message_group_id if startswith(product_id, prefix)
    ])
  }
}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    jobs_handler = {
      handler     = "com.jordansimsmith.pricetracker.JobsHandler"
      artifact    = var.artifacts["jobs_handler"]
      memory_size = 1024
      timeout     = 120
    }
  }

  role_policy_arns = {
    dynamodb = aws_iam_policy.lambda_dynamodb.arn
    sns      = aws_iam_policy.lambda_sns.arn
    sqs      = aws_iam_policy.lambda_sqs.arn
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
    effect    = "Allow"
    resources = [aws_dynamodb_table.price_tracker.arn]

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
    effect    = "Allow"
    resources = [aws_sns_topic.price_updates.arn]

    actions = [
      "sns:Publish",
      "sns:GetTopicAttributes",
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
  name                        = "price_tracker_jobs_dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  message_retention_seconds   = 1209600
}

resource "aws_sqs_queue" "jobs" {
  name                        = "price_tracker_jobs.fifo"
  fifo_queue                  = true
  content_based_deduplication = true
  message_retention_seconds   = 1209600
  visibility_timeout_seconds  = 720

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_dlq.arn
    maxReceiveCount     = 5
  })
}

data "aws_iam_policy_document" "lambda_sqs" {
  statement {
    effect    = "Allow"
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
    effect    = "Allow"
    resources = [aws_sqs_queue.jobs.arn]
    actions   = ["sqs:SendMessage"]
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

resource "aws_scheduler_schedule" "update_product" {
  for_each                     = local.product_ids
  name                         = "${local.application_id}_update_${each.key}"
  description                  = "Queues the ${each.value} price update"
  schedule_expression          = "cron(0 * * * ? *)"
  schedule_expression_timezone = "UTC"
  state                        = contains(local.paused_product_schedules, each.key) ? "DISABLED" : "ENABLED"
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
        "MessageBody": "{\"job_type\":\"update_product\",\"product_id\":\"${each.value}\",\"scheduled_at\":\"<aws.scheduler.scheduled-time>\"}",
        "MessageGroupId": "${local.product_message_group_ids[each.key]}"
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
  description                  = "Queues the hourly price decrease digest"
  schedule_expression          = "cron(5 * * * ? *)"
  schedule_expression_timezone = "UTC"
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
        "MessageGroupId": "price-tracker-digest"
      }
    JSON

    retry_policy {
      maximum_event_age_in_seconds = 3600
      maximum_retry_attempts       = 5
    }
  }
}
