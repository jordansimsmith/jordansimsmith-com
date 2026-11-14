variable "name" {
  type = string
}

variable "visibility_timeout_seconds" {
  type = number
}

variable "fifo" {
  type    = bool
  default = false
}

variable "dlq" {
  type    = bool
  default = false
}

variable "content_based_deduplication" {
  type    = bool
  default = false

  validation {
    condition     = !var.content_based_deduplication || var.fifo
    error_message = "content_based_deduplication can only be enabled for a FIFO queue."
  }
}

variable "max_receive_count" {
  type    = number
  default = 5
}

variable "lambda_trigger" {
  type = object({
    function_arn                       = string
    batch_size                         = optional(number, 10)
    maximum_batching_window_in_seconds = optional(number, 0)
  })
  default = null
}
