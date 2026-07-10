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
  application_id = "immersion_tracker_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.immersion-tracker.jordansimsmith.com"
  cors_origin    = "https://immersion-tracker.jordansimsmith.com"

  lambdas = {
    get_progress = {
      handler  = "com.jordansimsmith.immersiontracker.GetProgressHandler"
      artifact = var.artifacts["get_progress"]
    }
    get_shows = {
      handler  = "com.jordansimsmith.immersiontracker.GetShowsHandler"
      artifact = var.artifacts["get_shows"]
    }
    sync_episodes = {
      handler  = "com.jordansimsmith.immersiontracker.SyncEpisodesHandler"
      artifact = var.artifacts["sync_episodes"]
    }
    update_shows = {
      handler  = "com.jordansimsmith.immersiontracker.UpdateShowHandler"
      artifact = var.artifacts["update_shows"]
    }
    sync_youtube = {
      handler  = "com.jordansimsmith.immersiontracker.SyncYoutubeHandler"
      artifact = var.artifacts["sync_youtube"]
    }
    sync_spotify = {
      handler  = "com.jordansimsmith.immersiontracker.SyncSpotifyHandler"
      artifact = var.artifacts["sync_spotify"]
    }
    sync_movies = {
      handler  = "com.jordansimsmith.immersiontracker.SyncMoviesHandler"
      artifact = var.artifacts["sync_movies"]
    }
  }

  endpoints = {
    get_progress  = { path = "progress", method = "GET", lambda = "get_progress" }
    get_shows     = { path = "shows", method = "GET", lambda = "get_shows" }
    sync_episodes = { path = "sync", method = "POST", lambda = "sync_episodes" }
    update_shows  = { path = "show", method = "PUT", lambda = "update_shows" }
    sync_youtube  = { path = "syncyoutube", method = "POST", lambda = "sync_youtube" }
    sync_spotify  = { path = "syncspotify", method = "POST", lambda = "sync_spotify" }
    sync_movies   = { path = "syncmovies", method = "POST", lambda = "sync_movies" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_dynamodb_table" "immersion_tracker_table" {
  name         = "immersion_tracker"
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

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = true
}

resource "aws_secretsmanager_secret" "immersion_tracker" {
  name                    = local.application_id
  recovery_window_in_days = 0
}

data "aws_iam_policy_document" "lambda_dynamodb_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_dynamodb_table.immersion_tracker_table.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:Scan",
      "dynamodb:Query",
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

data "aws_iam_policy_document" "lambda_secretsmanager_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      aws_secretsmanager_secret.immersion_tracker.arn
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
