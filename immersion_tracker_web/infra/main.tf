terraform {
  backend "s3" {
    bucket = "jordansimsmith-terraform"
    key    = "immersion_tracker_web/infra/terraform.tfstate"
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
  application_id = "immersion_tracker_web"
}

module "static_site" {
  source = "../../infra/modules/static_site"

  application_id = local.application_id
  bucket_name    = "immersion-tracker.jordansimsmith.com"
  domain_name    = "immersion-tracker.jordansimsmith.com"
  build_dir      = var.artifacts["build"]

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }
}
