resource "aws_sqs_queue" "dlq" {
  count = var.dlq ? 1 : 0

  name                        = "${var.name}_dlq${var.fifo ? ".fifo" : ""}"
  fifo_queue                  = var.fifo
  content_based_deduplication = var.fifo ? var.content_based_deduplication : null
}

resource "aws_sqs_queue" "queue" {
  name                        = "${var.name}${var.fifo ? ".fifo" : ""}"
  fifo_queue                  = var.fifo
  content_based_deduplication = var.fifo ? var.content_based_deduplication : null
  visibility_timeout_seconds  = var.visibility_timeout_seconds

  redrive_policy = var.dlq ? jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[0].arn
    maxReceiveCount     = var.max_receive_count
  }) : null
}

resource "aws_lambda_event_source_mapping" "lambda_trigger" {
  count = var.lambda_trigger == null ? 0 : 1

  event_source_arn                   = aws_sqs_queue.queue.arn
  function_name                      = var.lambda_trigger.function_arn
  batch_size                         = var.lambda_trigger.batch_size
  maximum_batching_window_in_seconds = var.lambda_trigger.maximum_batching_window_in_seconds
}
