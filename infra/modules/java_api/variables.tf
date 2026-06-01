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

  validation {
    condition     = var.authorization != "CUSTOM" || contains(keys(var.lambdas), "auth")
    error_message = "A lambda named \"auth\" is required when authorization is CUSTOM."
  }
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
    condition     = alltrue([for endpoint in var.endpoints : length(split("/", endpoint.path)) <= 2])
    error_message = "Endpoint paths support at most two segments (resource/{id})."
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
