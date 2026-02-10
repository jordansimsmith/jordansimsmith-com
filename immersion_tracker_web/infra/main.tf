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
  origin_id      = "${local.application_id}_s3_origin"
}

resource "aws_s3_bucket" "immersion_tracker_web" {
  bucket = "immersion-tracker.jordansimsmith.com"
}

resource "aws_s3_bucket_ownership_controls" "immersion_tracker_web" {
  bucket = aws_s3_bucket.immersion_tracker_web.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

module "immersion_tracker_web_dir" {
  source   = "hashicorp/dir/template"
  version  = "1.0.2"
  base_dir = var.artifacts["build"]
}

check "immersion_tracker_web_artifact_not_empty" {
  assert {
    condition     = length(module.immersion_tracker_web_dir.files) > 0
    error_message = "The resolved web artifact directory is empty for immersion_tracker_web. Check artifacts[\"build\"]."
  }
}

resource "aws_s3_object" "objects" {
  for_each = module.immersion_tracker_web_dir.files

  bucket       = aws_s3_bucket.immersion_tracker_web.id
  key          = each.key
  source       = each.value.source_path
  etag         = each.value.digests.md5
  content      = each.value.content
  content_type = each.value.content_type
}

resource "aws_s3_bucket_website_configuration" "immersion_tracker_web" {
  bucket = aws_s3_bucket.immersion_tracker_web.id

  index_document {
    suffix = "index.html"
  }
}

data "aws_iam_policy_document" "s3_cloudfront" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.immersion_tracker_web.arn}/*"]
    principals {
      identifiers = ["cloudfront.amazonaws.com"]
      type        = "Service"
    }
    condition {
      test     = "StringEquals"
      values   = [aws_cloudfront_distribution.immersion_tracker_web.arn]
      variable = "AWS:SourceArn"
    }
  }
}

resource "aws_s3_bucket_policy" "s3_cloudfront" {
  bucket = aws_s3_bucket.immersion_tracker_web.id
  policy = data.aws_iam_policy_document.s3_cloudfront.json
}

resource "aws_cloudfront_origin_access_control" "immersion_tracker_web" {
  name                              = "${local.application_id}_s3_access_control"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_acm_certificate" "immersion_tracker_web" {
  provider          = aws.us_east_1
  domain_name       = "immersion-tracker.jordansimsmith.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_cloudfront_distribution" "immersion_tracker_web" {
  origin {
    domain_name              = aws_s3_bucket.immersion_tracker_web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.immersion_tracker_web.id
    origin_id                = local.origin_id
  }

  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  price_class         = "PriceClass_All"

  aliases = [aws_acm_certificate.immersion_tracker_web.domain_name]

  default_cache_behavior {
    allowed_methods  = ["GET", "HEAD"]
    cached_methods   = ["GET", "HEAD"]
    target_origin_id = local.origin_id

    forwarded_values {
      query_string = false

      cookies {
        forward = "none"
      }
    }

    viewer_protocol_policy = "redirect-to-https"
    min_ttl                = 0
    default_ttl            = 3600
    max_ttl                = 86400
  }

  # SPA deep-link support: route 403/404 to index.html
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 0
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate.immersion_tracker_web.arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
