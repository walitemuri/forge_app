targetScope = 'subscription'

@description('Azure region for all Forge resources.')
param location string = 'northcentralus'

@description('Resource group created for the demo.')
param resourceGroupName string = 'forge-demo-rg'

@description('Linux VM name.')
param vmName string = 'forge-demo-vm'

@description('Budget default: 2 vCPU and 4 GiB RAM. Upgrade to Standard_B2as_v2 if needed.')
param vmSize string = 'Standard_B2als_v2'

@description('Linux administrator account.')
param adminUsername string = 'azureuser'

@description('Contents of an SSH public key, for example ~/.ssh/forge_azure.pub.')
@secure()
param sshPublicKey string

@description('CIDR allowed to SSH, normally your public IP followed by /32.')
param allowedSshCidr string

@description('Globally unique prefix for the free Azure cloudapp hostname.')
@minLength(3)
@maxLength(50)
param dnsLabelPrefix string = 'forge-${uniqueString(subscription().subscriptionId)}'

resource resourceGroup 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: resourceGroupName
  location: location
}

module forgeVm './vm.bicep' = {
  name: 'forge-vm'
  scope: resourceGroup
  params: {
    location: location
    vmName: vmName
    vmSize: vmSize
    adminUsername: adminUsername
    sshPublicKey: sshPublicKey
    allowedSshCidr: allowedSshCidr
    dnsLabelPrefix: dnsLabelPrefix
  }
}

output resourceGroupName string = resourceGroup.name
output vmName string = forgeVm.outputs.vmName
output publicIpAddress string = forgeVm.outputs.publicIpAddress
output hostname string = forgeVm.outputs.hostname
output sshCommand string = 'ssh ${adminUsername}@${forgeVm.outputs.hostname}'
