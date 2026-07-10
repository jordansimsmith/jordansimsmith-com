terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "book_tracker_api/infra/terraform.tfstate"
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
  application_id = "book_tracker_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.book-tracker.jordansimsmith.com"
  cors_origin    = "https://book-tracker.jordansimsmith.com"

  lambdas = {
    create_book = {
      handler  = "com.jordansimsmith.booktracker.CreateBookHandler"
      artifact = var.artifacts["create_book"]
    }
    find_books = {
      handler  = "com.jordansimsmith.booktracker.FindBooksHandler"
      artifact = var.artifacts["find_books"]
    }
    get_book = {
      handler  = "com.jordansimsmith.booktracker.GetBookHandler"
      artifact = var.artifacts["get_book"]
    }
    update_book = {
      handler  = "com.jordansimsmith.booktracker.UpdateBookHandler"
      artifact = var.artifacts["update_book"]
    }
    delete_book = {
      handler  = "com.jordansimsmith.booktracker.DeleteBookHandler"
      artifact = var.artifacts["delete_book"]
    }
  }

  endpoints = {
    create_book = { path = "books", method = "POST", lambda = "create_book" }
    find_books  = { path = "books", method = "GET", lambda = "find_books" }
    get_book    = { path = "books/{open_library_work_id}", method = "GET", lambda = "get_book" }
    update_book = { path = "books/{open_library_work_id}", method = "PUT", lambda = "update_book" }
    delete_book = { path = "books/{open_library_work_id}", method = "DELETE", lambda = "delete_book" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_dynamodb_table" "book_tracker" {
  name         = "book_tracker"
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

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.book_tracker.arn,
      "${aws_dynamodb_table.book_tracker.arn}/index/*"
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
