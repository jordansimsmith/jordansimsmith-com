variable "application_id" {
  type = string
}

variable "domain_name" {
  type = string
}

variable "lambdas" {
  type = map(object({
    handler     = string
    artifact    = string
    memory_size = optional(number, 1769)
    timeout     = optional(number, 10)
  }))
}

variable "role_policy_arns" {
  type    = map(string)
  default = {}
}

variable "endpoints" {
  type = map(object({
    path   = string
    method = string
    lambda = string
  }))
  default = {}

  validation {
    condition     = alltrue([for endpoint in var.endpoints : contains(keys(var.lambdas), endpoint.lambda)])
    error_message = "Every endpoint must reference a lambda defined in var.lambdas."
  }

  validation {
    condition     = alltrue([for endpoint in var.endpoints : length(split("/", endpoint.path)) <= 6])
    error_message = "Endpoint paths support at most six segments."
  }
}

variable "authorization" {
  type    = string
  default = "CUSTOM"

  validation {
    condition     = contains(["CUSTOM", "NONE"], var.authorization)
    error_message = "authorization must be either CUSTOM or NONE."
  }
}

variable "cors_origin" {
  type    = string
  default = null
}

variable "binary_media_types" {
  type    = list(string)
  default = []
}
