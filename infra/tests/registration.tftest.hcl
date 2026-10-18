mock_provider "msgraph" {}

override_data {
  target = data.msgraph_resource.existing
  values = {
    output = {
      client_id = "33333333-3333-4333-8333-333333333333"
    }
  }
}

override_resource {
  target = msgraph_resource.little_nest
  values = {
    output = {
      client_id = "33333333-3333-4333-8333-333333333333"
    }
  }
}

variables {
  tenant_id             = "11111111-1111-4111-8111-111111111111"
  application_object_id = "22222222-2222-4222-8222-222222222222"
  expected_client_id    = "33333333-3333-4333-8333-333333333333"
}

run "native_personal_account_configuration" {
  command = apply

  assert {
    condition = (
      msgraph_resource.little_nest.url == "applications" &&
      msgraph_resource.little_nest.api_version == "v1.0" &&
      msgraph_resource.little_nest.body.signInAudience == "PersonalMicrosoftAccount" &&
      msgraph_resource.little_nest.body.api.requestedAccessTokenVersion == 2
    )
    error_message = "Keep personal-account OAuth v2 on the stable Graph API."
  }

  assert {
    condition = (
      msgraph_resource.little_nest.body.publicClient.redirectUris == ["app.littlenest:/oauth"] &&
      strcontains(
        file("${path.module}/../app/src/main/java/app/littlenest/OneDriveAuth.java"),
        "Uri.parse(\"${msgraph_resource.little_nest.body.publicClient.redirectUris[0]}\")"
      )
    )
    error_message = "The native redirect must match OneDriveAuth and belong to the same registration."
  }

  assert {
    condition = (
      msgraph_resource.little_nest.body.requiredResourceAccess == [{
        resourceAppId = "00000003-0000-0000-c000-000000000000"
        resourceAccess = [{
          id   = "5c28f0bf-8a70-41f1-8ab2-9032436ddb65"
          type = "Scope"
        }]
      }]
    )
    error_message = "Request only delegated Files.ReadWrite, never app-only Graph roles."
  }

  assert {
    condition = (
      !contains(keys(msgraph_resource.little_nest.body), "passwordCredentials") &&
      !contains(keys(msgraph_resource.little_nest.body), "keyCredentials")
    )
    error_message = "A public native client must not create client secrets or certificates."
  }

  assert {
    condition     = output.onedrive_client_id == var.expected_client_id
    error_message = "The existing release client ID must be preserved."
  }
}

run "reject_wrong_registration" {
  command = plan

  variables {
    expected_client_id = "44444444-4444-4444-8444-444444444444"
  }

  expect_failures = [msgraph_resource.little_nest]
}

run "reject_invalid_tenant" {
  command = plan

  variables {
    tenant_id = "not-a-tenant"
  }

  expect_failures = [var.tenant_id]
}

run "reject_invalid_object_id" {
  command = plan

  variables {
    application_object_id = "not-an-object"
  }

  expect_failures = [var.application_object_id]
}

run "reject_invalid_client_id" {
  command = plan

  variables {
    expected_client_id = "not-a-client"
  }

  expect_failures = [var.expected_client_id]
}
