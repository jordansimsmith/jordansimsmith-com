module "dir" {
  source   = "hashicorp/dir/template"
  version  = "1.0.2"
  base_dir = var.build_dir
}

check "artifact_not_empty" {
  assert {
    condition     = length(module.dir.files) > 0
    error_message = "The resolved web artifact directory is empty for ${var.application_id}. Check build_dir."
  }
}

resource "aws_s3_bucket" "this" {
  bucket = var.bucket_name
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_object" "objects" {
  for_each = module.dir.files

  bucket        = aws_s3_bucket.this.id
  key           = each.key
  source        = each.value.source_path
  etag          = each.value.digests.md5
  content       = each.value.content
  content_type  = each.value.content_type
  cache_control = "no-cache"
}

resource "aws_s3_bucket_website_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  index_document {
    suffix = "index.html"
  }
}

data "aws_iam_policy_document" "this" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.this.arn}/*"]
    principals {
      identifiers = ["cloudfront.amazonaws.com"]
      type        = "Service"
    }
    condition {
      test     = "StringEquals"
      values   = [aws_cloudfront_distribution.this.arn]
      variable = "AWS:SourceArn"
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.this.json
}
