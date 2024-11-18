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
  application_id = "immersion_tracker_api"

  lambdas = {
    auth = {
      target  = "//immersion_tracker_api:auth-handler_deploy.jar"
      handler = "com.jordansimsmith.immersiontracker.AuthHandler"
    }
    get_progress = {
      target  = "//immersion_tracker_api:get-progress-handler_deploy.jar"
      handler = "com.jordansimsmith.immersiontracker.GetProgressHandler"
    }
    get_shows = {
      target  = "//immersion_tracker_api:get-shows-handler_deploy.jar"
      handler = "com.jordansimsmith.immersiontracker.GetShowsHandler"
    }
    sync_episodes = {
      target  = "//immersion_tracker_api:sync-episodes-handler_deploy.jar"
      handler = "com.jordansimsmith.immersiontracker.SyncEpisodesHandler"
    }
    update_shows = {
      target  = "//immersion_tracker_api:update-show-handler_deploy.jar"
      handler = "com.jordansimsmith.immersiontracker.UpdateShowHandler"
    }
  }

  endpoints = {
    get_progress = {
      path   = "progress"
      method = "GET"
    }
    get_shows = {
      path   = "shows"
      method = "GET"
    }
    sync_episodes = {
      path   = "sync"
      method = "POST"
    }
    update_shows = {
      path   = "show"
      method = "PUT"
    }
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
}

resource "aws_secretsmanager_secret" "immersion_tracker" {
  name                    = local.application_id
  recovery_window_in_days = 0
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
      aws_dynamodb_table.immersion_tracker_table.arn
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
}

resource "aws_iam_role_policy_attachment" "lambda_secretsmanager" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
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
  memory_size      = 512
  timeout          = 10
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = local.lambdas

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda[each.key].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.immersion_tracker.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "immersion_tracker" {
  name = "${local.application_id}_gateway"
}

resource "aws_api_gateway_authorizer" "immersion_tracker" {
  name                             = "${local.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.immersion_tracker.id
  authorizer_uri                   = aws_lambda_function.lambda["auth"].invoke_arn
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

resource "aws_api_gateway_resource" "resource" {
  for_each = local.endpoints

  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id
  parent_id   = aws_api_gateway_rest_api.immersion_tracker.root_resource_id
  path_part   = each.value.path
}

resource "aws_api_gateway_method" "method" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id   = aws_api_gateway_resource.resource[each.key].id
  http_method   = each.value.method
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.immersion_tracker.id
}

resource "aws_api_gateway_integration" "integration" {
  for_each = local.endpoints

  rest_api_id             = aws_api_gateway_rest_api.immersion_tracker.id
  resource_id             = aws_api_gateway_resource.resource[each.key].id
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.lambda[each.key].invoke_arn
}

resource "aws_api_gateway_deployment" "immersion_tracker" {
  rest_api_id = aws_api_gateway_rest_api.immersion_tracker.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.immersion_tracker,
      aws_api_gateway_gateway_response.unauthorized,
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
  deployment_id = aws_api_gateway_deployment.immersion_tracker.id
  rest_api_id   = aws_api_gateway_rest_api.immersion_tracker.id
  stage_name    = "prod"
}

resource "aws_acm_certificate" "immersion_tracker" {
  provider          = aws.us_east_1
  domain_name       = "api.immersion-tracker.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_domain_name" "immersion_tracker" {
  domain_name     = aws_acm_certificate.immersion_tracker.domain_name
  certificate_arn = aws_acm_certificate.immersion_tracker.arn
}

resource "aws_api_gateway_base_path_mapping" "immersion_tracker" {
  api_id      = aws_api_gateway_rest_api.immersion_tracker.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  domain_name = aws_api_gateway_domain_name.immersion_tracker.domain_name
}
