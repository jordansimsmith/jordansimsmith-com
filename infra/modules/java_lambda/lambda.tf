data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "lambda_role" {
  name               = "${var.application_id}_lambda_exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "lambda_xray" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_lambda_function" "lambda" {
  for_each = var.lambdas

  filename         = each.value.artifact
  function_name    = "${var.application_id}_${each.key}"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = filebase64sha256(each.value.artifact)
  handler          = each.value.handler
  runtime          = "java21"
  memory_size      = each.value.memory_size
  timeout          = each.value.timeout
  architectures    = ["x86_64"]
  publish          = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  tracing_config {
    mode = "Active"
  }
}
