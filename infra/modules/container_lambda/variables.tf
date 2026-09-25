variable "application_id" {
  type = string
}

variable "name" {
  type = string
}

variable "image_uri" {
  type = string
}

variable "memory_size" {
  type    = number
  default = 1769
}

variable "timeout" {
  type    = number
  default = 10
}

variable "architectures" {
  type    = list(string)
  default = ["x86_64"]
}

variable "publish" {
  type    = bool
  default = true
}

variable "environment" {
  type    = map(string)
  default = {}
}

variable "log_retention_in_days" {
  type    = number
  default = 30
}

variable "role_policy_arns" {
  type    = map(string)
  default = {}
}

variable "event_source_mappings" {
  type = map(object({
    event_source_arn                   = string
    batch_size                         = optional(number, 10)
    maximum_batching_window_in_seconds = optional(number, 0)
    enabled                            = optional(bool, true)
  }))
  default = {}
}
