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
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.japanese-dictionary.jordansimsmith.com"
  cors_origin    = "https://japanese-dictionary.jordansimsmith.com"

  lambdas = {
    search = {
      handler  = "com.jordansimsmith.japanesedictionary.SearchHandler"
      artifact = var.artifacts["search"]
      timeout  = 5
    }
    create_bookmark = {
      handler  = "com.jordansimsmith.japanesedictionary.CreateBookmarkHandler"
      artifact = var.artifacts["create_bookmark"]
    }
    delete_bookmark = {
      handler  = "com.jordansimsmith.japanesedictionary.DeleteBookmarkHandler"
      artifact = var.artifacts["delete_bookmark"]
    }
    find_bookmarks = {
      handler  = "com.jordansimsmith.japanesedictionary.FindBookmarksHandler"
      artifact = var.artifacts["find_bookmarks"]
    }
  }

  endpoints = {
    search          = { path = "search", method = "GET", lambda = "search" }
    find_bookmarks  = { path = "bookmarks", method = "GET", lambda = "find_bookmarks" }
    create_bookmark = { path = "bookmarks/{sequence}", method = "PUT", lambda = "create_bookmark" }
    delete_bookmark = { path = "bookmarks/{sequence}", method = "DELETE", lambda = "delete_bookmark" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
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
