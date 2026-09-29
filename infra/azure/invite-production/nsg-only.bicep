targetScope = 'resourceGroup'

@description('Azure region for the invite-production application NSG.')
param location string = resourceGroup().location

var environmentName = 'invite-production'
var commonTags = {
  application: 'parkio'
  environment: environmentName
  lifecycle: 'awaiting-cutover-authorization'
  package: 'PROD-DEPLOY-01A'
}

// Scoped entrypoint: deploy only nsg-parkio-invite-app via the canonical module.
// Does not touch VM, PostgreSQL, storage, Key Vault, role assignments, or VNet.
module appNsg 'modules/app-nsg.bicep' = {
  name: 'inviteProductionAppNsg'
  params: {
    location: location
    tags: commonTags
  }
}

output nsgName string = appNsg.outputs.nsgName
output nsgId string = appNsg.outputs.nsgId
