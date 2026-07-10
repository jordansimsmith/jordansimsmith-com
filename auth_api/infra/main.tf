terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "auth_api/infra/terraform.tfstate"
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
  application_id = "auth_api"
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

module "java_lambda" {
  source = "../../infra/modules/java_lambda"

  application_id = local.application_id

  lambdas = {
    auth = {
      handler  = "com.jordansimsmith.auth.AuthHandler"
      artifact = var.artifacts["auth"]
    }
  }
}

# consumers reference the authorizer through this stable alias so their
# authorizer uri does not change when a new lambda version is published
resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = module.java_lambda.lambda_functions["auth"].function_name
  function_version = module.java_lambda.lambda_functions["auth"].version
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = module.java_lambda.lambda_functions["auth"].function_name
  qualifier     = aws_lambda_alias.live.name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "arn:aws:execute-api:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:*"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_secretsmanager_secret" "auth" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.auth.arn
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
  role       = module.java_lambda.lambda_role_name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}
