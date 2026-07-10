terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "anki_backup_api/infra/terraform.tfstate"
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
  application_id = "anki_backup_api"
}

module "java_api" {
  source = "../../infra/modules/java_api"

  application_id = local.application_id
  domain_name    = "api.anki-backup.jordansimsmith.com"

  lambdas = {
    create_backup = {
      handler  = "com.jordansimsmith.ankibackup.CreateBackupHandler"
      artifact = var.artifacts["create_backup"]
      timeout  = 30
    }
    update_backup = {
      handler  = "com.jordansimsmith.ankibackup.UpdateBackupHandler"
      artifact = var.artifacts["update_backup"]
      timeout  = 30
    }
    find_backups = {
      handler  = "com.jordansimsmith.ankibackup.FindBackupsHandler"
      artifact = var.artifacts["find_backups"]
      timeout  = 30
    }
    get_backup = {
      handler  = "com.jordansimsmith.ankibackup.GetBackupHandler"
      artifact = var.artifacts["get_backup"]
      timeout  = 30
    }
  }

  endpoints = {
    create_backup = { path = "backups", method = "POST", lambda = "create_backup" }
    find_backups  = { path = "backups", method = "GET", lambda = "find_backups" }
    update_backup = { path = "backups/{backup_id}", method = "PUT", lambda = "update_backup" }
    get_backup    = { path = "backups/{backup_id}", method = "GET", lambda = "get_backup" }
  }

  providers = {
    aws.us_east_1 = aws.us_east_1
  }
}

resource "aws_s3_bucket" "anki_backup" {
  bucket = "anki-backup.jordansimsmith.com"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  rule {
    id     = "expire-backups"
    status = "Enabled"

    filter {}

    expiration {
      days = 90
    }
  }

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_public_access_block" "anki_backup" {
  bucket = aws_s3_bucket.anki_backup.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "anki_backup" {
  name         = "anki_backup"
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

  ttl {
    attribute_name = "ttl"
    enabled        = true
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
      aws_dynamodb_table.anki_backup.arn
    ]

    actions = [
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:GetItem",
      "dynamodb:Query",
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

data "aws_iam_policy_document" "lambda_s3_allow_policy_document" {
  statement {
    effect = "Allow"

    resources = [
      "${aws_s3_bucket.anki_backup.arn}/*"
    ]

    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
  }

  statement {
    effect    = "Allow"
    resources = ["*"]
    actions   = ["s3:ListAllMyBuckets"]
  }
}

resource "aws_iam_policy" "lambda_s3" {
  name   = "${local.application_id}_lambda_s3"
  policy = data.aws_iam_policy_document.lambda_s3_allow_policy_document.json
}

resource "aws_iam_role_policy_attachment" "lambda_s3" {
  role       = module.java_api.lambda_role_name
  policy_arn = aws_iam_policy.lambda_s3.arn
}
