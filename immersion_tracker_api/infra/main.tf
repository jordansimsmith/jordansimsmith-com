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

data "external" "get_progress_handler_location" {
  program = ["bash", "${path.module}/resolve_location.sh"]

  query = {
    target = "//immersion_tracker_api:get-progress-handler_deploy.jar"
  }
}

data "local_file" "get_progress_handler_file" {
  filename = data.external.get_progress_handler_location.result.location
}

data "aws_iam_policy_document" "lambda_policy_document" {
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
  name               = "lambda_role"
  assume_role_policy = data.aws_iam_policy_document.lambda_policy_document.json
}

resource "aws_lambda_function" "get_progress_handler_lambda" {
  filename         = data.local_file.get_progress_handler_file.filename
  function_name    = "get_progress_handler"
  role             = aws_iam_role.lambda_role.arn
  source_code_hash = data.local_file.get_progress_handler_file.content_base64sha256
  handler          = "com.jordansimsmith.immersiontracker.GetProgressHandler"
  runtime          = "java17"
}

