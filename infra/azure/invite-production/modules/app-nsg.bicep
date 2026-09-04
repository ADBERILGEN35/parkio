@description('Azure region for the invite-production application NSG.')
param location string

@description('Resource tags applied to the NSG.')
param tags object

@description('Canonical invite-production application NSG name.')
param nsgName string = 'nsg-parkio-invite-app'

// Canonical Internet ingress surface for invite-production public edge.
// Consumed by main.bicep (full foundation) and nsg-only.bicep (scoped deploy).
resource appNsg 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: nsgName
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'Allow-Https-From-Internet'
        properties: {
          priority: 100
          access: 'Allow'
          direction: 'Inbound'
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '443'
          sourceAddressPrefix: 'Internet'
          destinationAddressPrefix: '*'
        }
      }
      // PROD-DEPLOY-01B-03C1: TCP :80 for future Caddy ACME HTTP-01 + HTTP->HTTPS
      // redirect only. NSG allow does not start Caddy, authorize ACME, or cut over DNS.
      {
        name: 'Allow-Http-From-Internet'
        properties: {
          priority: 110
          access: 'Allow'
          direction: 'Inbound'
          protocol: 'Tcp'
          sourcePortRange: '*'
          destinationPortRange: '80'
          sourceAddressPrefix: 'Internet'
          destinationAddressPrefix: '*'
        }
      }
    ]
  }
}

output nsgId string = appNsg.id
output nsgName string = appNsg.name
