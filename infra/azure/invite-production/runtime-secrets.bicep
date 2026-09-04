targetScope = 'resourceGroup'

@description('Existing invite-production Key Vault name.')
param keyVaultName string

@secure()
@description('Generated first-party production secret names and values.')
param runtimeSecrets object

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
  name: keyVaultName
}

resource secrets 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = [for secret in items(runtimeSecrets): {
  parent: keyVault
  name: secret.key
  properties: {
    value: string(secret.value)
  }
}]
