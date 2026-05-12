terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "japanese_dictionary_api/infra/terraform.tfstate"
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
  application_id = "japanese_dictionary_api"
  cors_origins   = ["https://japanese-dictionary.jordansimsmith.com"]

  lambdas = {
    auth = {
      target      = "//japanese_dictionary_api:auth-handler_deploy.jar"
      handler     = "com.jordansimsmith.japanesedictionary.AuthHandler"
      memory_size = 512
      timeout     = 10
    }
    search = {
      target      = "//japanese_dictionary_api:search-handler_deploy.jar"
      handler     = "com.jordansimsmith.japanesedictionary.SearchHandler"
      memory_size = 1024
      timeout     = 5
    }
    create_bookmark = {
      target      = "//japanese_dictionary_api:create-bookmark-handler_deploy.jar"
      handler     = "com.jordansimsmith.japanesedictionary.CreateBookmarkHandler"
      memory_size = 512
      timeout     = 10
    }
    find_bookmarks = {
      target      = "//japanese_dictionary_api:find-bookmarks-handler_deploy.jar"
      handler     = "com.jordansimsmith.japanesedictionary.FindBookmarksHandler"
      memory_size = 512
      timeout     = 10
    }
  }

  root_resources = {
    search    = { path = "search" }
    bookmarks = { path = "bookmarks" }
  }

  child_resources = {
    bookmark = { path = "{sequence}", parent = "bookmarks" }
  }

  all_resources = merge(local.root_resources, local.child_resources)

  endpoints = {
    search          = { resource = "search", method = "GET", lambda = "search" }
    find_bookmarks  = { resource = "bookmarks", method = "GET", lambda = "find_bookmarks" }
    create_bookmark = { resource = "bookmark", method = "PUT", lambda = "create_bookmark" }
  }

  all_resource_ids = merge(
    { for k, v in aws_api_gateway_resource.root_resource : k => v.id },
    { for k, v in aws_api_gateway_resource.child_resource : k => v.id }
  )
}

check "unique_resource_paths" {
  assert {
    condition     = length(local.all_resources) == length(distinct([for r in local.all_resources : r.path]))
    error_message = "Resource paths must be unique"
  }
}

check "valid_endpoint_resources" {
  assert {
    condition     = alltrue([for e in local.endpoints : contains(keys(local.all_resources), e.resource)])
    error_message = "All endpoint resources must reference a valid resource key"
  }
}

check "valid_child_resource_parents" {
  assert {
    condition     = alltrue([for r in local.child_resources : contains(keys(local.root_resources), r.parent)])
    error_message = "All child resource parents must reference a valid root resource key"
  }
}

check "valid_endpoint_lambdas" {
  assert {
    condition     = alltrue([for e in local.endpoints : contains(keys(local.lambdas), e.lambda)])
    error_message = "All endpoint lambdas must reference a valid lambda key"
  }
}

resource "aws_secretsmanager_secret" "japanese_dictionary" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

resource "aws_dynamodb_table" "japanese_dictionary" {
  name         = "japanese_dictionary"
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

  attribute {
    name = "gsi3pk"
    type = "S"
  }

  attribute {
    name = "gsi3sk"
    type = "S"
  }

  global_secondary_index {
    name               = "gsi1"
    hash_key           = "gsi1pk"
    range_key          = "gsi1sk"
    projection_type    = "INCLUDE"
    non_key_attributes = ["sequence", "frequency_rank"]
  }

  global_secondary_index {
    name               = "gsi2"
    hash_key           = "gsi2pk"
    range_key          = "gsi2sk"
    projection_type    = "INCLUDE"
    non_key_attributes = ["sequence", "frequency_rank"]
  }

  global_secondary_index {
    name               = "gsi3"
    hash_key           = "gsi3pk"
    range_key          = "gsi3sk"
    projection_type    = "INCLUDE"
    non_key_attributes = ["sequence", "frequency_rank"]
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

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.japanese_dictionary.arn
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

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.japanese_dictionary.arn,
      "${aws_dynamodb_table.japanese_dictionary.arn}/index/*"
    ]

    actions = [
      "dynamodb:Query",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:PutItem",
      "dynamodb:DeleteItem",
      "dynamodb:BatchWriteItem"
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

resource "aws_lambda_function" "lambda" {
  for_each = local.lambdas

  filename         = var.artifacts[each.key]
  function_name    = "${local.application_id}_${each.key}"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = filebase64sha256(var.artifacts[each.key])
  handler          = each.value.handler
  runtime          = "java21"
  memory_size      = each.value.memory_size
  timeout          = each.value.timeout
  architectures    = ["x86_64"]
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = local.lambdas

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.lambda[each.key].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.japanese_dictionary.execution_arn}/*/*"
}

resource "aws_api_gateway_rest_api" "japanese_dictionary" {
  name = "${local.application_id}_gateway"
}

resource "aws_api_gateway_authorizer" "japanese_dictionary" {
  name                             = "${local.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.japanese_dictionary.id
  authorizer_uri                   = aws_lambda_function.lambda["auth"].invoke_arn
  type                             = "REQUEST"
  identity_source                  = "method.request.header.Authorization"
  authorizer_result_ttl_in_seconds = 0
}

resource "aws_api_gateway_gateway_response" "unauthorized" {
  rest_api_id   = aws_api_gateway_rest_api.japanese_dictionary.id
  status_code   = "401"
  response_type = "UNAUTHORIZED"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = {
    "gatewayresponse.header.WWW-Authenticate" = "'Basic'"
  }
}

resource "aws_api_gateway_resource" "root_resource" {
  for_each = local.root_resources

  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id
  parent_id   = aws_api_gateway_rest_api.japanese_dictionary.root_resource_id
  path_part   = each.value.path
}

resource "aws_api_gateway_resource" "child_resource" {
  for_each = local.child_resources

  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id
  parent_id   = aws_api_gateway_resource.root_resource[each.value.parent].id
  path_part   = each.value.path
}

resource "aws_api_gateway_method" "method" {
  for_each = local.endpoints

  rest_api_id   = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id   = local.all_resource_ids[each.value.resource]
  http_method   = each.value.method
  authorization = "CUSTOM"
  authorizer_id = aws_api_gateway_authorizer.japanese_dictionary.id
}

resource "aws_api_gateway_integration" "integration" {
  for_each = local.endpoints

  rest_api_id             = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id             = local.all_resource_ids[each.value.resource]
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_function.lambda[each.value.lambda].invoke_arn
}

resource "aws_api_gateway_method" "options" {
  for_each = local.all_resources

  rest_api_id   = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id   = local.all_resource_ids[each.key]
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options" {
  for_each = local.all_resources

  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id = local.all_resource_ids[each.key]
  http_method = aws_api_gateway_method.options[each.key].http_method
  type        = "MOCK"

  request_templates = {
    "application/json" = "{\"statusCode\": 200}"
  }
}

resource "aws_api_gateway_method_response" "options" {
  for_each = local.all_resources

  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id = local.all_resource_ids[each.key]
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
  for_each = local.all_resources

  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id
  resource_id = local.all_resource_ids[each.key]
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = aws_api_gateway_method_response.options[each.key].status_code

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Authorization,Content-Type'"
    "method.response.header.Access-Control-Allow-Methods" = "'GET,PUT,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'${local.cors_origins[0]}'"
  }
}

resource "aws_api_gateway_deployment" "japanese_dictionary" {
  rest_api_id = aws_api_gateway_rest_api.japanese_dictionary.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.japanese_dictionary,
      aws_api_gateway_gateway_response.unauthorized,
      aws_api_gateway_resource.root_resource,
      aws_api_gateway_resource.child_resource,
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
  deployment_id = aws_api_gateway_deployment.japanese_dictionary.id
  rest_api_id   = aws_api_gateway_rest_api.japanese_dictionary.id
  stage_name    = "prod"
}

resource "aws_acm_certificate" "japanese_dictionary" {
  provider          = aws.us_east_1
  domain_name       = "api.japanese-dictionary.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_domain_name" "japanese_dictionary" {
  domain_name     = aws_acm_certificate.japanese_dictionary.domain_name
  certificate_arn = aws_acm_certificate.japanese_dictionary.arn
}

resource "aws_api_gateway_base_path_mapping" "japanese_dictionary" {
  api_id      = aws_api_gateway_rest_api.japanese_dictionary.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  domain_name = aws_api_gateway_domain_name.japanese_dictionary.domain_name
}
