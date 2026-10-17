terraform {
  required_version = ">= 1.7, < 2.0"

  required_providers {
    msgraph = {
      source  = "microsoft/msgraph"
      version = "~> 0.6.0"
    }
  }
}

provider "msgraph" {
  tenant_id = var.tenant_id
}
