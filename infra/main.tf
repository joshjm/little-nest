data "msgraph_resource" "existing" {
  url = "applications/${var.application_object_id}"
  response_export_values = {
    client_id = "appId"
  }
}

resource "msgraph_resource" "little_nest" {
  url                     = "applications"
  api_version             = "v1.0"
  ignore_missing_property = false

  # Graph accepts the existing RFC 8252 native redirect; AzureAD 3.10 rejects it.
  body = {
    displayName    = "Little Nest"
    signInAudience = "PersonalMicrosoftAccount"
    api = {
      requestedAccessTokenVersion = 2
    }
    publicClient = {
      redirectUris = ["app.littlenest:/oauth"]
    }
    requiredResourceAccess = [{
      resourceAppId = "00000003-0000-0000-c000-000000000000"
      resourceAccess = [{
        id   = "5c28f0bf-8a70-41f1-8ab2-9032436ddb65" # Files.ReadWrite (delegated).
        type = "Scope"
      }]
    }]
  }

  response_export_values = {
    client_id = "appId"
  }

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = lower(data.msgraph_resource.existing.output.client_id) == lower(var.expected_client_id)
      error_message = "The existing application's client ID differs from the release client ID. Check the tenant and application object ID before adopting it."
    }

    postcondition {
      condition     = lower(self.output.client_id) == lower(var.expected_client_id)
      error_message = "The imported application's client ID differs from the release client ID. Stop; do not replace the registration."
    }
  }
}
