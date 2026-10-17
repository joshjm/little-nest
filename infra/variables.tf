variable "tenant_id" {
  type        = string
  description = "Directory tenant that owns the publisher's existing app registration (not a caregiver account)."
  nullable    = false

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.tenant_id))
    error_message = "tenant_id must be the owning Entra directory's UUID."
  }
}

variable "application_object_id" {
  type        = string
  description = "Object ID of the existing Little Nest application registration, not its client ID or service principal ID."
  nullable    = false

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.application_object_id))
    error_message = "application_object_id must be the existing app registration's object UUID."
  }
}

variable "expected_client_id" {
  type        = string
  description = "Existing public client ID used by release builds; adoption fails if the imported app does not match."
  nullable    = false

  validation {
    condition     = can(regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", var.expected_client_id))
    error_message = "expected_client_id must be the existing application's client UUID."
  }
}
