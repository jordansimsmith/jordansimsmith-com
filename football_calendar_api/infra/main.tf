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

locals {
  application_id = "football_calendar_api"

  lambdas = {
    get_calendar_subscription = {
      target  = "//football_calendar_api:get-calendar-subscription-handler_deploy.jar"
      handler = "com.jordansimsmith.footballcalendar.GetCalendarSubscriptionHandler"
    }
    update_fixtures = {
      target  = "//football_calendar_api:update-fixtures-handler_deploy.jar"
      handler = "com.jordansimsmith.footballcalendar.UpdateFixturesHandler"
    }
  }

  endpoints = {
    get_calendar_subscription = {
      path   = "calendar"
      method = "GET"
    }
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
    ]
  }
}

resource "aws_iam_policy" "lambda_dynamodb" {
  name   = "${local.application_id}_lambda_dynamodb"
  policy = data.aws_iam_policy_document.lambda_dynamodb_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
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
}

resource "aws_cloudwatch_event_rule" "update_fixtures_hourly" {
  name                = "${local.application_id}_update_fixtures_hourly"
  description         = "Trigger the UpdateFixturesHandler Lambda function every hour"
  schedule_expression = "rate(1 hour)"
}

resource "aws_cloudwatch_event_target" "update_fixtures_lambda" {
  rule      = aws_cloudwatch_event_rule.update_fixtures_hourly.name
  target_id = "UpdateFixturesHandler"
  arn       = aws_lambda_function.lambda["update_fixtures"].arn
}

resource "aws_lambda_permission" "allow_eventbridge" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda["update_fixtures"].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.update_fixtures_hourly.arn
}


resource "aws_lambda_permission" "api_gateway" {
  for_each = local.endpoints

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda[each.key].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.football_calendar.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "football_calendar" {
  name = "${local.application_id}_gateway"
}

resource "aws_api_gateway_resource" "resource" {
  for_each = local.endpoints

  rest_api_id = aws_api_gateway_rest_api.football_calendar.id
  parent_id   = aws_api_gateway_rest_api.football_calendar.root_resource_id
  path_part   = each.value.path
}

resource "aws_api_gateway_method" "method" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.football_calendar.id
  resource_id   = aws_api_gateway_resource.resource[each.key].id
  http_method   = each.value.method
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "integration" {
  for_each = local.endpoints

  rest_api_id             = aws_api_gateway_rest_api.football_calendar.id
  resource_id             = aws_api_gateway_resource.resource[each.key].id
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.lambda[each.key].invoke_arn
}

resource "aws_api_gateway_deployment" "football_calendar" {
  rest_api_id = aws_api_gateway_rest_api.football_calendar.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_resource.resource,
      aws_api_gateway_method.method,
      aws_api_gateway_integration.integration,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  deployment_id = aws_api_gateway_deployment.football_calendar.id
  rest_api_id   = aws_api_gateway_rest_api.football_calendar.id
  stage_name    = "prod"
}

resource "aws_acm_certificate" "football_calendar" {
  provider          = aws.us_east_1
  domain_name       = "api.football-calendar.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_domain_name" "football_calendar" {
  domain_name     = aws_acm_certificate.football_calendar.domain_name
  certificate_arn = aws_acm_certificate.football_calendar.arn
}

resource "aws_api_gateway_base_path_mapping" "football_calendar" {
  api_id      = aws_api_gateway_rest_api.football_calendar.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  domain_name = aws_api_gateway_domain_name.football_calendar.domain_name
}