locals {
  cors_enabled = var.cors_origin != null

  cors_allow_headers = "Authorization,Content-Type"
  cors_allow_methods = "GET,POST,PUT,DELETE,OPTIONS"

  api_lambdas = toset(concat(
    [for endpoint in var.endpoints : endpoint.lambda],
    var.authorization == "CUSTOM" ? ["auth"] : [],
  ))

  resource_paths = toset(flatten([
    for endpoint in var.endpoints : [
      for depth in range(length(split("/", endpoint.path))) :
      join("/", slice(split("/", endpoint.path), 0, depth + 1))
    ]
  ]))

  root_resources = toset([for path in local.resource_paths : path if length(split("/", path)) == 1])

  child_resources = {
    for path in local.resource_paths : path => {
      path_part = element(split("/", path), 1)
      parent    = element(split("/", path), 0)
    } if length(split("/", path)) == 2
  }

  resource_ids = merge(
    { for path, resource in aws_api_gateway_resource.root_resource : path => resource.id },
    { for path, resource in aws_api_gateway_resource.child_resource : path => resource.id },
  )

  options_resources = local.cors_enabled ? local.resource_paths : toset([])
}

resource "aws_api_gateway_rest_api" "this" {
  name = "${var.application_id}_gateway"
}

resource "aws_api_gateway_resource" "root_resource" {
  for_each = local.root_resources

  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_rest_api.this.root_resource_id
  path_part   = each.value
}

resource "aws_api_gateway_resource" "child_resource" {
  for_each = local.child_resources

  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.root_resource[each.value.parent].id
  path_part   = each.value.path_part
}

resource "aws_api_gateway_authorizer" "this" {
  count = var.authorization == "CUSTOM" ? 1 : 0

  name                             = "${var.application_id}_authorizer"
  rest_api_id                      = aws_api_gateway_rest_api.this.id
  authorizer_uri                   = module.lambda.lambda_functions["auth"].qualified_invoke_arn
  type                             = "REQUEST"
  identity_source                  = "method.request.header.Authorization"
  authorizer_result_ttl_in_seconds = 300
}

resource "aws_api_gateway_method" "method" {
  for_each = var.endpoints

  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = local.resource_ids[each.value.path]
  http_method   = each.value.method
  authorization = var.authorization
  authorizer_id = var.authorization == "CUSTOM" ? aws_api_gateway_authorizer.this[0].id : null
}

resource "aws_api_gateway_integration" "integration" {
  for_each = var.endpoints

  rest_api_id             = aws_api_gateway_rest_api.this.id
  resource_id             = local.resource_ids[each.value.path]
  http_method             = aws_api_gateway_method.method[each.key].http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = module.lambda.lambda_functions[each.value.lambda].qualified_invoke_arn
}

resource "aws_api_gateway_gateway_response" "unauthorized" {
  count = var.authorization == "CUSTOM" ? 1 : 0

  rest_api_id   = aws_api_gateway_rest_api.this.id
  status_code   = "401"
  response_type = "UNAUTHORIZED"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = merge(
    {
      "gatewayresponse.header.WWW-Authenticate" = "'Basic'"
    },
    local.cors_enabled ? {
      "gatewayresponse.header.Access-Control-Allow-Origin"  = "'${var.cors_origin}'"
      "gatewayresponse.header.Access-Control-Allow-Headers" = "'${local.cors_allow_headers}'"
      "gatewayresponse.header.Access-Control-Allow-Methods" = "'${local.cors_allow_methods}'"
    } : {},
  )
}

resource "aws_api_gateway_gateway_response" "default_4xx" {
  count = local.cors_enabled ? 1 : 0

  rest_api_id   = aws_api_gateway_rest_api.this.id
  response_type = "DEFAULT_4XX"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = {
    "gatewayresponse.header.Access-Control-Allow-Origin"  = "'${var.cors_origin}'"
    "gatewayresponse.header.Access-Control-Allow-Headers" = "'${local.cors_allow_headers}'"
    "gatewayresponse.header.Access-Control-Allow-Methods" = "'${local.cors_allow_methods}'"
  }
}

resource "aws_api_gateway_gateway_response" "default_5xx" {
  count = local.cors_enabled ? 1 : 0

  rest_api_id   = aws_api_gateway_rest_api.this.id
  response_type = "DEFAULT_5XX"

  response_templates = {
    "application/json" = "{\"message\":$context.error.messageString}"
  }

  response_parameters = {
    "gatewayresponse.header.Access-Control-Allow-Origin"  = "'${var.cors_origin}'"
    "gatewayresponse.header.Access-Control-Allow-Headers" = "'${local.cors_allow_headers}'"
    "gatewayresponse.header.Access-Control-Allow-Methods" = "'${local.cors_allow_methods}'"
  }
}

resource "aws_api_gateway_method" "options" {
  for_each = local.options_resources

  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = local.resource_ids[each.key]
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options" {
  for_each = local.options_resources

  rest_api_id = aws_api_gateway_rest_api.this.id
  resource_id = local.resource_ids[each.key]
  http_method = aws_api_gateway_method.options[each.key].http_method
  type        = "MOCK"

  request_templates = {
    "application/json" = "{\"statusCode\": 200}"
  }
}

resource "aws_api_gateway_method_response" "options" {
  for_each = local.options_resources

  rest_api_id = aws_api_gateway_rest_api.this.id
  resource_id = local.resource_ids[each.key]
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
  for_each = local.options_resources

  rest_api_id = aws_api_gateway_rest_api.this.id
  resource_id = local.resource_ids[each.key]
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = aws_api_gateway_method_response.options[each.key].status_code

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'${local.cors_allow_headers}'"
    "method.response.header.Access-Control-Allow-Methods" = "'${local.cors_allow_methods}'"
    "method.response.header.Access-Control-Allow-Origin"  = "'${var.cors_origin}'"
  }
}

resource "aws_api_gateway_deployment" "this" {
  rest_api_id = aws_api_gateway_rest_api.this.id

  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_authorizer.this,
      aws_api_gateway_gateway_response.unauthorized,
      aws_api_gateway_gateway_response.default_4xx,
      aws_api_gateway_gateway_response.default_5xx,
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
  deployment_id        = aws_api_gateway_deployment.this.id
  rest_api_id          = aws_api_gateway_rest_api.this.id
  stage_name           = "prod"
  xray_tracing_enabled = true
}
