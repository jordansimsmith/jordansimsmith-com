terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "immersion_tracker_api/infra/terraform.tfstate"
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
  application_id = "immersion_tracker_api"
  tags = {
    application_id = local.application_id
  }
}

resource "aws_dynamodb_table" "immersion_tracker_table" {
  name         = "immersion_tracker"
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

  tags = local.tags
}

resource "aws_secretsmanager_secret" "immersion_tracker" {
  name                    = local.application_id
  recovery_window_in_days = 0
  tags                    = local.tags
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
  tags               = local.tags
}

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.immersion_tracker_table.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
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
  tags   = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.immersion_tracker.arn
    ]

    actions = [
      "secretsmanager:GetResourcePolicy",
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds"
    ]
  }

  statement {
    effect = "Allow"

    resources = [
      "*"
    ]

    actions = [
      "secretsmanager:ListSecrets"
    ]
  }
}

resource "aws_iam_policy" "lambda_secretsmanager" {
  name   = "${local.application_id}_lambda_secretsmanager"
  policy = data.aws_iam_policy_document.lambda_secretsmanager_allow_policy_document.json
  tags   = local.tags
}

resource "aws_iam_role_policy_attachment" "lambda_secretsmanager" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}

data "external" "auth_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:auth-handler_deploy.jar"
  }
}

data "local_file" "auth_handler_file" {
  filename = data.external.auth_handler_location.result.location
}

resource "aws_lambda_function" "auth" {
  filename         = data.local_file.auth_handler_file.filename
  function_name    = "${local.application_id}_auth"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.auth_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.AuthHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

data "external" "get_progress_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:get-progress-handler_deploy.jar"
  }
}

data "local_file" "get_progress_handler_file" {
  filename = data.external.get_progress_handler_location.result.location
}

resource "aws_lambda_function" "get_progress" {
  filename         = data.local_file.get_progress_handler_file.filename
  function_name    = "${local.application_id}_get_progress"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.get_progress_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.GetProgressHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

data "external" "get_shows_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:get-shows-handler_deploy.jar"
  }
}

data "local_file" "get_shows_handler_file" {
  filename = data.external.get_shows_handler_location.result.location
}

resource "aws_lambda_function" "get_shows" {
  filename         = data.local_file.get_shows_handler_file.filename
  function_name    = "${local.application_id}_get_shows"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.get_shows_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.GetShowsHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

data "external" "update_show_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:update-show-handler_deploy.jar"
  }
}

data "local_file" "update_show_handler_file" {
  filename = data.external.update_show_handler_location.result.location
}

resource "aws_lambda_function" "update_show" {
  filename         = data.local_file.update_show_handler_file.filename
  function_name    = "${local.application_id}_update_show"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.update_show_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.UpdateShowHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

data "external" "sync_episodes_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:sync-episodes-handler_deploy.jar"
  }
}

data "local_file" "sync_episodes_handler_file" {
  filename = data.external.sync_episodes_handler_location.result.location
}

resource "aws_lambda_function" "sync_episodes" {
  filename         = data.local_file.sync_episodes_handler_file.filename
  function_name    = "${local.application_id}_sync_episodes"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.sync_episodes_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.SyncEpisodesHandler"
  runtime          = "java17"
  memory_size      = 512
  timeout          = 10
  tags             = local.tags
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = toset([
    aws_lambda_function.auth.function_name,
    aws_lambda_function.get_progress.function_name,
    aws_lambda_function.get_shows.function_name,
    aws_lambda_function.update_show.function_name,
    aws_lambda_function.sync_episodes.function_name,
  ])

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = each.key
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.immersion_tracker.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "immersion_tracker" {
  name = "${local.application_id}_gateway"
  tags = local.tags
}

resource "aws_api_gateway_authorizer" "immersion_tracker" {
  name                             = "${local.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.immersion_tracker.id
  authorizer_uri                   = aws_lambda_function.auth.invoke_arn
  type                             = "REQUEST"
  identity_source                  = "method.request.header.Authorization,method.request.querystring.user"
  authorizer_result_ttl_in_seconds = 0
}

resource "aws_api_gateway_gateway_response" "unauthorized" {
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  status_code   = "401"
  response_type = "UNAUTHORIZED"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = {
    "gatewayresponse.header.WWW-Authenticate" = "'Basic'"
  }
}

resource "aws_api_gateway_resource" "get_progress" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id
  parent_id   = aws_api_gateway_rest_api.immersion_tracker.root_resource_id
  path_part   = "progress"
}

resource "aws_api_gateway_method" "get_progress" {
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id   = aws_api_gateway_resource.get_progress.id
  http_method   = "GET"
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.immersion_tracker.id
}

resource "aws_api_gateway_integration" "get_progress" {
  rest_api_id             = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id             = aws_api_gateway_resource.get_progress.id
  http_method             = aws_api_gateway_method.get_progress.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.get_progress.invoke_arn
}

resource "aws_api_gateway_resource" "get_shows" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id
  parent_id   = aws_api_gateway_rest_api.immersion_tracker.root_resource_id
  path_part   = "shows"
}

resource "aws_api_gateway_method" "get_shows" {
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id   = aws_api_gateway_resource.get_shows.id
  http_method   = "GET"
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.immersion_tracker.id
}

resource "aws_api_gateway_integration" "get_shows" {
  rest_api_id             = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id             = aws_api_gateway_resource.get_shows.id
  http_method             = aws_api_gateway_method.get_shows.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.get_shows.invoke_arn
}

resource "aws_api_gateway_resource" "update_show" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id
  parent_id   = aws_api_gateway_rest_api.immersion_tracker.root_resource_id
  path_part   = "show"
}

resource "aws_api_gateway_method" "update_show" {
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id   = aws_api_gateway_resource.update_show.id
  http_method   = "PUT"
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.immersion_tracker.id
}

resource "aws_api_gateway_integration" "update_show" {
  rest_api_id             = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id             = aws_api_gateway_resource.update_show.id
  http_method             = aws_api_gateway_method.update_show.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.update_show.invoke_arn
}

resource "aws_api_gateway_resource" "sync_episodes" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id
  parent_id   = aws_api_gateway_rest_api.immersion_tracker.root_resource_id
  path_part   = "sync"
}

resource "aws_api_gateway_method" "sync_episodes" {
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id   = aws_api_gateway_resource.sync_episodes.id
  http_method   = "POST"
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.immersion_tracker.id
}

resource "aws_api_gateway_integration" "sync_episodes" {
  rest_api_id             = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id             = aws_api_gateway_resource.sync_episodes.id
  http_method             = aws_api_gateway_method.sync_episodes.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.sync_episodes.invoke_arn
}

resource "aws_api_gateway_deployment" "immersion_tracker" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.immersion_tracker,
      aws_api_gateway_gateway_response.unauthorized,
      aws_api_gateway_resource.get_progress,
      aws_api_gateway_method.get_progress,
      aws_api_gateway_integration.get_progress,
      aws_api_gateway_resource.get_shows,
      aws_api_gateway_method.get_shows,
      aws_api_gateway_integration.get_shows,
      aws_api_gateway_resource.update_show,
      aws_api_gateway_method.update_show,
      aws_api_gateway_integration.update_show,
      aws_api_gateway_resource.sync_episodes,
      aws_api_gateway_method.sync_episodes,
      aws_api_gateway_integration.sync_episodes,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  deployment_id = aws_api_gateway_deployment.immersion_tracker.id
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  stage_name    = "prod"
}
