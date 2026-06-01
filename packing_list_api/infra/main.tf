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

variable "artifacts" {
  type = map(string)
}

locals {
  application_id = "packing_list_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.packing-list.jordansimsmith.com"
  cors_origin    = "https://packing-list.jordansimsmith.com"

  lambdas = {
    auth = {
      handler  = "com.jordansimsmith.packinglist.AuthHandler"
      artifact = var.artifacts["auth"]
    }
    get_templates = {
      handler  = "com.jordansimsmith.packinglist.GetTemplatesHandler"
      artifact = var.artifacts["get_templates"]
    }
    create_trip = {
      handler  = "com.jordansimsmith.packinglist.CreateTripHandler"
      artifact = var.artifacts["create_trip"]
    }
    find_trips = {
      handler  = "com.jordansimsmith.packinglist.FindTripsHandler"
      artifact = var.artifacts["find_trips"]
    }
    get_trip = {
      handler  = "com.jordansimsmith.packinglist.GetTripHandler"
      artifact = var.artifacts["get_trip"]
    }
    update_trip = {
      handler  = "com.jordansimsmith.packinglist.UpdateTripHandler"
      artifact = var.artifacts["update_trip"]
    }
    delete_trip = {
      handler  = "com.jordansimsmith.packinglist.DeleteTripHandler"
      artifact = var.artifacts["delete_trip"]
    }
  }

  endpoints = {
    get_templates = { path = "templates", method = "GET", lambda = "get_templates" }
    create_trip   = { path = "trips", method = "POST", lambda = "create_trip" }
    find_trips    = { path = "trips", method = "GET", lambda = "find_trips" }
    get_trip      = { path = "trips/{trip_id}", method = "GET", lambda = "get_trip" }
    update_trip   = { path = "trips/{trip_id}", method = "PUT", lambda = "update_trip" }
    delete_trip   = { path = "trips/{trip_id}", method = "DELETE", lambda = "delete_trip" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_secretsmanager_secret" "packing_list" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

resource "aws_dynamodb_table" "packing_list" {
  name         = "packing_list"
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

  global_secondary_index {
    name            = "gsi1"
    hash_key        = "gsi1pk"
    range_key       = "gsi1sk"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true
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
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_secretsmanager.arn
}

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.packing_list.arn,
      "${aws_dynamodb_table.packing_list.arn}/index/*"
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      "dynamodb:DeleteItem",
      "dynamodb:ConditionCheckItem",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["dynamodb:ListTables"]
  }
}

resource "aws_iam_policy" "lambda_dynamodb" {
  name   = "${local.application_id}_lambda_dynamodb"
  policy = data.aws_iam_policy_document.lambda_dynamodb_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_dynamodb" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_dynamodb.arn
}
