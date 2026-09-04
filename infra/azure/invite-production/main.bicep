targetScope = 'resourceGroup'

@description('Azure region for every invite-production resource.')
param location string = resourceGroup().location

@description('Object ID of the operator allowed to populate production secrets.')
param operatorObjectId string

@description('OpenSSH public key for the emergency VM operator account. Port 22 remains blocked by the NSG.')
param sshPublicKey string

@secure()
@description('Initial PostgreSQL administrator password. The provisioning wrapper stores it in Key Vault after deployment and never logs it.')
param administratorLoginPassword string

@description('PostgreSQL administrator login used only for database bootstrap and operator recovery.')
param administratorLogin string = 'parkioops'

@description('Emergency VM operator account. SSH is key-only and port 22 remains blocked.')
param vmAdministratorLogin string = 'parkioops'

param vmSize string = 'Standard_E4bds_v5'
param postgresqlSku string = 'Standard_D2ds_v5'
param postgresqlStorageGb int = 128

var environmentName = 'invite-production'
var nameSuffix = take(uniqueString(subscription().subscriptionId, resourceGroup().id), 6)
var vmName = 'vm-parkio-invite-prod'
var vnetName = 'vnet-parkio-invite-prod'
var appSubnetName = 'snet-app'
var databaseSubnetName = 'snet-postgresql'
var postgresqlName = 'pg-parkio-invite-${nameSuffix}'
var privateDnsZoneName = 'invite.parkio.postgres.database.azure.com'
var keyVaultName = 'kvparkioinv${nameSuffix}'
var storageAccountName = 'stparkioinv${nameSuffix}'
var databaseNames = [
  'parkio_auth'
  'parkio_gateway'
  'parkio_user'
  'parkio_parking'
  'parkio_media'
  'parkio_gamification'
  'parkio_notification'
  'parkio_moderation'
  'parkio_analytics'
  'parkio_aivalidation'
]
var commonTags = {
  application: 'parkio'
  environment: environmentName
  lifecycle: 'awaiting-cutover-authorization'
  package: 'PROD-DEPLOY-01A'
}

module appNsg 'modules/app-nsg.bicep' = {
  name: 'inviteProductionAppNsg'
  params: {
    location: location
    tags: commonTags
  }
}

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  tags: commonTags
  properties: {
    addressSpace: {
      addressPrefixes: [
        '10.42.0.0/16'
      ]
    }
  }
}

resource appSubnet 'Microsoft.Network/virtualNetworks/subnets@2024-05-01' = {
  parent: vnet
  name: appSubnetName
  properties: {
    addressPrefix: '10.42.1.0/24'
    networkSecurityGroup: {
      id: appNsg.outputs.nsgId
    }
    serviceEndpoints: [
      {
        service: 'Microsoft.KeyVault'
      }
      {
        service: 'Microsoft.Storage'
      }
    ]
  }
}

resource databaseSubnet 'Microsoft.Network/virtualNetworks/subnets@2024-05-01' = {
  parent: vnet
  name: databaseSubnetName
  properties: {
    addressPrefix: '10.42.2.0/24'
    delegations: [
      {
        name: 'postgresql-flexible-server'
        properties: {
          serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
        }
      }
    ]
  }
}

resource privateDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: privateDnsZoneName
  location: 'global'
  tags: commonTags
}

resource privateDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: privateDnsZone
  name: 'link-${vnetName}'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: vnet.id
    }
  }
}

resource publicIp 'Microsoft.Network/publicIPAddresses@2024-05-01' = {
  name: 'pip-parkio-invite-prod'
  location: location
  zones: [
    '1'
    '2'
    '3'
  ]
  sku: {
    name: 'Standard'
    tier: 'Regional'
  }
  tags: commonTags
  properties: {
    publicIPAllocationMethod: 'Static'
    publicIPAddressVersion: 'IPv4'
    idleTimeoutInMinutes: 4
  }
}

resource nic 'Microsoft.Network/networkInterfaces@2024-05-01' = {
  name: 'nic-parkio-invite-prod'
  location: location
  tags: commonTags
  properties: {
    enableAcceleratedNetworking: true
    ipConfigurations: [
      {
        name: 'ipconfig1'
        properties: {
          privateIPAllocationMethod: 'Dynamic'
          subnet: {
            id: appSubnet.id
          }
          publicIPAddress: {
            id: publicIp.id
          }
        }
      }
    ]
  }
}

resource vm 'Microsoft.Compute/virtualMachines@2024-07-01' = {
  name: vmName
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  tags: commonTags
  properties: {
    hardwareProfile: {
      vmSize: vmSize
    }
    storageProfile: {
      imageReference: {
        publisher: 'Canonical'
        offer: 'ubuntu-24_04-lts'
        sku: 'server'
        version: 'latest'
      }
      osDisk: {
        createOption: 'FromImage'
        diskSizeGB: 128
        managedDisk: {
          storageAccountType: 'Premium_LRS'
        }
      }
      dataDisks: [
        {
          lun: 0
          createOption: 'Empty'
          diskSizeGB: 256
          caching: 'ReadOnly'
          managedDisk: {
            storageAccountType: 'Premium_LRS'
          }
        }
      ]
    }
    osProfile: {
      computerName: vmName
      adminUsername: vmAdministratorLogin
      linuxConfiguration: {
        disablePasswordAuthentication: true
        provisionVMAgent: true
        patchSettings: {
          assessmentMode: 'AutomaticByPlatform'
          patchMode: 'AutomaticByPlatform'
        }
        ssh: {
          publicKeys: [
            {
              path: '/home/${vmAdministratorLogin}/.ssh/authorized_keys'
              keyData: sshPublicKey
            }
          ]
        }
      }
    }
    networkProfile: {
      networkInterfaces: [
        {
          id: nic.id
          properties: {
            primary: true
          }
        }
      ]
    }
    diagnosticsProfile: {
      bootDiagnostics: {
        enabled: true
      }
    }
    securityProfile: {
      securityType: 'TrustedLaunch'
      uefiSettings: {
        secureBootEnabled: true
        vTpmEnabled: true
      }
    }
  }
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  tags: commonTags
  properties: {
    tenantId: tenant().tenantId
    enableRbacAuthorization: true
    enablePurgeProtection: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Deny'
      ipRules: []
      virtualNetworkRules: [
        {
          id: appSubnet.id
          ignoreMissingVnetServiceEndpoint: false
        }
      ]
    }
    sku: {
      family: 'A'
      name: 'standard'
    }
  }
}

resource postgresqlAdministratorSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: 'postgresql-administrator-password'
  properties: {
    value: administratorLoginPassword
  }
}

resource backupStorage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageAccountName
  location: location
  tags: commonTags
  kind: 'StorageV2'
  sku: {
    name: 'Standard_GRS'
  }
  properties: {
    accessTier: 'Cool'
    allowBlobPublicAccess: false
    allowCrossTenantReplication: false
    allowSharedKeyAccess: false
    defaultToOAuthAuthentication: true
    minimumTlsVersion: 'TLS1_2'
    publicNetworkAccess: 'Enabled'
    supportsHttpsTrafficOnly: true
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Deny'
      ipRules: []
      virtualNetworkRules: [
        {
          id: appSubnet.id
          action: 'Allow'
        }
      ]
    }
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: backupStorage
  name: 'default'
  properties: {
    deleteRetentionPolicy: {
      enabled: true
      days: 30
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: 30
    }
    isVersioningEnabled: true
  }
}

resource backupContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: 'invite-production-backups'
  properties: {
    publicAccess: 'None'
  }
}

resource postgresql 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: postgresqlName
  location: location
  tags: commonTags
  sku: {
    name: postgresqlSku
    tier: 'GeneralPurpose'
  }
  properties: {
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorLoginPassword
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
      tenantId: tenant().tenantId
    }
    availabilityZone: '1'
    backup: {
      backupRetentionDays: 30
      geoRedundantBackup: 'Disabled'
    }
    createMode: 'Default'
    highAvailability: {
      mode: 'Disabled'
    }
    maintenanceWindow: {
      customWindow: 'Enabled'
      dayOfWeek: 0
      startHour: 2
      startMinute: 0
    }
    network: {
      delegatedSubnetResourceId: databaseSubnet.id
      privateDnsZoneArmResourceId: privateDnsZone.id
      publicNetworkAccess: 'Disabled'
    }
    storage: {
      autoGrow: 'Enabled'
      storageSizeGB: postgresqlStorageGb
      type: 'Premium_LRS'
    }
    version: '16'
  }
  dependsOn: [
    privateDnsLink
  ]
}

resource azureExtensions 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: postgresql
  name: 'azure.extensions'
  properties: {
    source: 'user-override'
    value: 'POSTGIS'
  }
}

resource databases 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = [for databaseName in databaseNames: {
  parent: postgresql
  name: databaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}]

var keyVaultSecretsUserRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4633458b-17de-408a-b874-0445c86b69e6')
var keyVaultSecretsOfficerRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7')
var storageBlobDataContributorRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'ba92f5b4-2d11-453d-a403-e96b0029c9fe')
var readerRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'acdd72a7-3385-48ef-bd42-f606fba81ae7')

resource vmKeyVaultSecretsUser 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, vm.id, keyVaultSecretsUserRole)
  scope: keyVault
  properties: {
    principalId: vm.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: keyVaultSecretsUserRole
  }
}

resource operatorKeyVaultSecretsOfficer 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, operatorObjectId, keyVaultSecretsOfficerRole)
  scope: keyVault
  properties: {
    principalId: operatorObjectId
    principalType: 'User'
    roleDefinitionId: keyVaultSecretsOfficerRole
  }
}

resource vmBackupContributor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(backupStorage.id, vm.id, storageBlobDataContributorRole)
  scope: backupStorage
  properties: {
    principalId: vm.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: storageBlobDataContributorRole
  }
}

resource vmResourceGroupReader 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(resourceGroup().id, vm.id, readerRole)
  properties: {
    principalId: vm.identity.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: readerRole
  }
}

output environment string = environmentName
output vmName string = vm.name
output vmSize string = vmSize
output vmPrincipalId string = vm.identity.principalId
output publicIpAddress string = publicIp.properties.ipAddress
output privateAppAddress string = nic.properties.ipConfigurations[0].properties.privateIPAddress
output postgresqlName string = postgresql.name
output postgresqlFqdn string = postgresql.properties.fullyQualifiedDomainName
output postgresqlSku string = postgresqlSku
output postgresqlPublicAccess string = postgresql.properties.network.publicNetworkAccess
output postgresqlBackupRetentionDays int = postgresql.properties.backup.backupRetentionDays
output postgresqlHighAvailability string = postgresql.properties.highAvailability.mode
output privateDnsZone string = privateDnsZone.name
output keyVaultName string = keyVault.name
output backupStorageAccount string = backupStorage.name
output backupContainer string = backupContainer.name
output databaseNames array = databaseNames
