module "lambda" {
  source = "../java_lambda"

  application_id = var.application_id
  lambdas        = var.lambdas
}

resource "aws_lambda_permission" "api_gateway" {
  for_each = local.api_lambdas

  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.lambda.lambda_functions[each.key].function_name
  qualifier     = module.lambda.lambda_functions[each.key].version
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.this.execution_arn}/*/*"

  lifecycle {
    create_before_destroy = true
  }
}
