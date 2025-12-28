terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "packing_list_api/infra/terraform.tfstate"
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
  application_id = "packing_list_api"
  cors_origins   = ["https://packing-list.jordansimsmith.com"]

  lambdas = {
    auth = {
      target  = "//packing_list_api:auth-handler_deploy.jar"
      handler = "com.jordansimsmith.packinglist.AuthHandler"
    }
    get_templates = {
      target  = "//packing_list_api:get-templates-handler_deploy.jar"
      handler = "com.jordansimsmith.packinglist.GetTemplatesHandler"
    }
  }

  endpoints = {
    get_templates = {
      path   = "templates"
      method = "GET"
    }
  }
}

resource "aws_secretsmanager_secret" "packing_list" {
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

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.packing_list.arn
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
  architectures    = ["x86_64"]
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = local.lambdas

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda[each.key].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.packing_list.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "packing_list" {
  name = "${local.application_id}_gateway"
}

resource "aws_api_gateway_authorizer" "packing_list" {
  name                             = "${local.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.packing_list.id
  authorizer_uri                   = aws_lambda_function.lambda["auth"].invoke_arn
  type                             = "REQUEST"
  identity_source                  = "method.request.header.Authorization,method.request.querystring.user"
  authorizer_result_ttl_in_seconds = 0
}

resource "aws_api_gateway_gateway_response" "unauthorized" {
  rest_api_id   = aws_api_gateway_rest_api.packing_list.id
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

  rest_api_id = aws_api_gateway_rest_api.packing_list.id
  parent_id   = aws_api_gateway_rest_api.packing_list.root_resource_id
  path_part   = each.value.path
}

resource "aws_api_gateway_method" "method" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.packing_list.id
  resource_id   = aws_api_gateway_resource.resource[each.key].id
  http_method   = each.value.method
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.packing_list.id
}

resource "aws_api_gateway_integration" "integration" {
  for_each = local.endpoints

  rest_api_id             = aws_api_gateway_rest_api.packing_list.id
  resource_id             = aws_api_gateway_resource.resource[each.key].id
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.lambda[each.key].invoke_arn
}

# CORS: OPTIONS method for /templates
resource "aws_api_gateway_method" "options" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.packing_list.id
  resource_id   = aws_api_gateway_resource.resource[each.key].id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options" {
  for_each = local.endpoints

  rest_api_id = aws_api_gateway_rest_api.packing_list.id
  resource_id = aws_api_gateway_resource.resource[each.key].id
  http_method = aws_api_gateway_method.options[each.key].http_method
  type        = "MOCK"

  request_templates = {
    "application/json" = "{\"statusCode\": 200}"
  }
}

resource "aws_api_gateway_method_response" "options" {
  for_each = local.endpoints

  rest_api_id = aws_api_gateway_rest_api.packing_list.id
  resource_id = aws_api_gateway_resource.resource[each.key].id
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = "200"

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }

  response_models = {
    "application/json" = "Empty"
  }
}

resource "aws_api_gateway_integration_response" "options" {
  for_each = local.endpoints

  rest_api_id = aws_api_gateway_rest_api.packing_list.id
  resource_id = aws_api_gateway_resource.resource[each.key].id
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = aws_api_gateway_method_response.options[each.key].status_code

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Authorization,Content-Type'"
    "method.response.header.Access-Control-Allow-Methods" = "'GET,POST,PUT,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'https://packing-list.jordansimsmith.com'"
  }
}

resource "aws_api_gateway_deployment" "packing_list" {
  rest_api_id = aws_api_gateway_rest_api.packing_list.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.packing_list,
      aws_api_gateway_gateway_response.unauthorized,
      aws_api_gateway_resource.resource,
      aws_api_gateway_method.method,
      aws_api_gateway_integration.integration,
      aws_api_gateway_method.options,
      aws_api_gateway_integration.options,
      aws_api_gateway_method_response.options,
      aws_api_gateway_integration_response.options,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  deployment_id = aws_api_gateway_deployment.packing_list.id
  rest_api_id   = aws_api_gateway_rest_api.packing_list.id
  stage_name    = "prod"
}

resource "aws_acm_certificate" "packing_list" {
  provider          = aws.us_east_1
  domain_name       = "api.packing-list.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_domain_name" "packing_list" {
  domain_name     = aws_acm_certificate.packing_list.domain_name
  certificate_arn = aws_acm_certificate.packing_list.arn
}

resource "aws_api_gateway_base_path_mapping" "packing_list" {
  api_id      = aws_api_gateway_rest_api.packing_list.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  domain_name = aws_api_gateway_domain_name.packing_list.domain_name
}

