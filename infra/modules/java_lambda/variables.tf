variable "application_id" {
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
