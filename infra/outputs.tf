output "onedrive_client_id" {
  description = "Public application ID for ONEDRIVE_CLIENT_ID when building the APK; not a client secret."
  value       = msgraph_resource.little_nest.output.client_id
}
