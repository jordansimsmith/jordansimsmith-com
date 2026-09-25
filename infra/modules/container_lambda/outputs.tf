output "lambda_function" {
  value = aws_lambda_function.lambda
}

output "lambda_function_arn" {
  value = aws_lambda_function.lambda.arn
}

output "lambda_role_arn" {
  value = aws_iam_role.lambda_role.arn
}

output "lambda_role_name" {
  value = aws_iam_role.lambda_role.name
}

output "event_source_mappings" {
  value = aws_lambda_event_source_mapping.lambda
}
