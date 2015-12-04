package com.eucalyptus.tests.awssdk

import com.amazonaws.AmazonServiceException
import com.amazonaws.Request
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.handlers.AbstractRequestHandler
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AccountAttribute
import com.amazonaws.services.ec2.model.CreateNetworkInterfaceRequest
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest
import com.amazonaws.services.ec2.model.CreateSubnetRequest
import com.amazonaws.services.ec2.model.CreateVpcRequest
import com.amazonaws.services.ec2.model.DeleteNetworkInterfaceRequest
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest
import com.amazonaws.services.ec2.model.DeleteSubnetRequest
import com.amazonaws.services.ec2.model.DeleteVpcRequest
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult
import com.amazonaws.services.ec2.model.DescribeImagesRequest
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeNetworkInterfacesRequest
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.InstanceNetworkInterfaceSpecification
import com.amazonaws.services.ec2.model.NetworkInterface
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.RunInstancesRequest
import com.amazonaws.services.ec2.model.TerminateInstancesRequest
import com.amazonaws.services.identitymanagement.model.CreateAccessKeyRequest
import com.amazonaws.services.identitymanagement.model.NoSuchEntityException
import com.github.sjones4.youcan.youare.YouAre
import com.github.sjones4.youcan.youare.YouAreClient
import com.github.sjones4.youcan.youare.model.CreateAccountRequest
import com.github.sjones4.youcan.youare.model.DeleteAccountRequest
import org.testng.Assert
import org.testng.annotations.Test

import static com.eucalyptus.tests.awssdk.Eutester4j.ACCESS_KEY
import static com.eucalyptus.tests.awssdk.Eutester4j.EC2_ENDPOINT
import static com.eucalyptus.tests.awssdk.Eutester4j.HOST_IP
import static com.eucalyptus.tests.awssdk.Eutester4j.SECRET_KEY
import static com.eucalyptus.tests.awssdk.Eutester4j.minimalInit

/**
 * Tests EC2 VPC running instances with multiple network interfaces.
 *
 * Related issues:
 *   https://eucalyptus.atlassian.net/browse/EUCA-11800
 */
class TestEC2VPCMultipleNetworkInterfaces {

  private final String host
  private final AWSCredentialsProvider credentials

  TestEC2VPCMultipleNetworkInterfaces( ) {
    minimalInit()
    this.host = HOST_IP
    this.credentials = new StaticCredentialsProvider( new BasicAWSCredentials( ACCESS_KEY, SECRET_KEY ) )
  }

  private String cloudUri( String host, String servicePath ) {
    URI.create( "http://${host}:8773/" )
        .resolve( servicePath )
        .toString( )
  }

  private YouAreClient getYouAreClient( final AWSCredentialsProvider credentials  ) {
    final YouAreClient euare = new YouAreClient( credentials )
    if ( host ) {
      euare.setEndpoint( cloudUri( host, '/services/Euare' ) )
    } else {
      euare.setRegion( Region.getRegion( Regions.US_EAST_1 ) )
    }
    euare
  }

  private AmazonEC2 getEC2Client( final AWSCredentialsProvider credentials ) {
    final AmazonEC2 ec2 = new AmazonEC2Client( credentials )
    if ( host ) {
      ec2.setEndpoint( EC2_ENDPOINT )
    } else {
      ec2.setRegion( Region.getRegion( Regions.US_WEST_1 ) )
    }
    ec2
  }

  private boolean assertThat( boolean condition,
                              String message ){
    Assert.assertTrue( condition, message )
    true
  }

  private void print( String text ) {
    System.out.println( text )
  }

  @Test
  void testEC2VPCMultipleNetworkInterfacesTest( ) throws Exception {
    final String namePrefix = UUID.randomUUID().toString().substring(0,8) + "-"
    print( "Using resource prefix for test: ${namePrefix}" )

    final List<Runnable> cleanupTasks = [] as List<Runnable>
    try {
      AWSCredentialsProvider adminCredentials = null
      adminCredentials = getYouAreClient( credentials ).with {
        // Create account to use for test
        final String accountName = "${namePrefix}account1"
        print("Creating test account: ${accountName}")
        String adminAccountNumber = createAccount(new CreateAccountRequest(accountName: accountName)).with {
          account?.accountId
        }
        assertThat(adminAccountNumber != null, 'Expected account number')
        print("Created test account with number: ${adminAccountNumber}")
        cleanupTasks.add {
          print("Deleting test account: ${accountName}")
          deleteAccount(new DeleteAccountRequest(accountName: accountName, recursive: true))
        }

        // Get credentials for test account
        print("Creating access key for test account admin user: ${accountName}")
        YouAre adminIam = getYouAreClient( credentials )
        adminIam.addRequestHandler(new AbstractRequestHandler() {
          public void beforeRequest(final Request<?> request) {
            request.addParameter('DelegateAccount', accountName)
          }
        })
        adminCredentials = adminIam.with {
          createAccessKey(new CreateAccessKeyRequest(userName: 'admin')).with {
            accessKey?.with {
              new StaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey))
            }
          }
        }
        assertThat(adminCredentials != null, 'Expected test acount admin user credentials')
        print("Created test acount admin user access key: ${adminCredentials.credentials.AWSAccessKeyId}")

        adminCredentials
      }

      getEC2Client( adminCredentials ).with {
        // Find an AZ to use
        final DescribeAvailabilityZonesResult azResult = describeAvailabilityZones( )

        assertThat( azResult.availabilityZones.size() > 0, 'Availability zone not found' );

        final String availabilityZone = azResult.availabilityZones.get( 0 ).zoneName;
        print( "Using availability zone: ${availabilityZone}" );

        // Find an image to use
        final String imageId = describeImages( new DescribeImagesRequest(
            filters: [
                new Filter( name: "image-type", values: ["machine"] ),
                new Filter( name: "root-device-type", values: ["instance-store"] ),
            ]
        ) ).with {
          images?.getAt( 0 )?.imageId
        }
        assertThat( imageId != null , "Image not found (instance-store)" )
        print( "Using image: ${imageId}" )

        // Verify that VPC is available
        describeAccountAttributes( ).with{
          boolean vpcPlatformAvailable = accountAttributes
              .find{ AccountAttribute attribute -> attribute.attributeName=='supported-platforms' }
              .attributeValues*.attributeValue.contains( 'VPC' )
          assertThat( vpcPlatformAvailable, "VPC platform not available: ${accountAttributes}" )
        }

        // Create VPC
        String vpcId = createVpc( new CreateVpcRequest( cidrBlock: '10.10.0.0/16' ) ).with {
          vpc?.vpcId
        }
        print( "Created VPC: ${vpcId}" )
        cleanupTasks.add{
          print( "Deleting VPC: ${vpcId}" )
          deleteVpc( new DeleteVpcRequest( vpcId: vpcId ))
        }

        // Create subnets
        print( "Creating subnet 1" )
        String subnet1Id = createSubnet( new CreateSubnetRequest(
          availabilityZone: availabilityZone,
          vpcId:  vpcId,
          cidrBlock: '10.10.1.0/24'
        ) ).with {
          subnet?.subnetId
        }
        print( "Created subnet 1: ${subnet1Id}" )
        cleanupTasks.add{
          print( "Deleting subnet 1: ${subnet1Id}" )
          deleteSubnet( new DeleteSubnetRequest( subnetId: subnet1Id ))
        }

        print( "Creating subnet 2" )
        String subnet2Id = createSubnet( new CreateSubnetRequest(
            availabilityZone: availabilityZone,
            vpcId:  vpcId,
            cidrBlock: '10.10.2.0/24'
        ) ).with {
          subnet?.subnetId
        }
        print( "Created subnet 2: ${subnet2Id}" )
        cleanupTasks.add{
          print( "Deleting subnet 2: ${subnet2Id}" )
          deleteSubnet( new DeleteSubnetRequest( subnetId: subnet2Id ))
        }

        // Create security groups
        print( "Creating security group 1" )
        String group1Id = createSecurityGroup( new CreateSecurityGroupRequest(
            groupName: 'group-1',
            description: 'group-1',
            vpcId: vpcId ) ).with {
          groupId
        }
        print( "Created security group 1: ${group1Id}" )
        cleanupTasks.add{
          print( "Deleting security group 1: ${group1Id}" )
          deleteSecurityGroup( new DeleteSecurityGroupRequest( groupId: group1Id ) )
        }

        print( "Creating security group 2" )
        String group2Id = createSecurityGroup( new CreateSecurityGroupRequest(
            groupName: 'group-2',
            description: 'group-2',
            vpcId: vpcId ) ).with {
          groupId
        }
        print( "Created security group 2: ${group2Id}" )
        cleanupTasks.add{
          print( "Deleting security group 2: ${group2Id}" )
          deleteSecurityGroup( new DeleteSecurityGroupRequest( groupId: group2Id ) )
        }

        // Create network interfaces
        print( "Creating network interface 1" )
        String eni1Id = createNetworkInterface( new CreateNetworkInterfaceRequest(
            description: 'network-interface-1',
            subnetId: subnet1Id,
            groups: [ group1Id ]
        ) ).with {
          networkInterface?.networkInterfaceId
        }
        print( "Created network interface 1: ${eni1Id}" )
        cleanupTasks.add{
          print( "Deleting network interface 1: ${eni1Id}" )
          deleteNetworkInterface( new DeleteNetworkInterfaceRequest( networkInterfaceId: eni1Id ) )
        }

        print( "Creating network interface 2" )
        String eni2Id = createNetworkInterface( new CreateNetworkInterfaceRequest(
            description: 'network-interface-2',
            subnetId: subnet2Id,
            groups: [ group2Id ]
        ) ).with {
          networkInterface?.networkInterfaceId
        }
        print( "Created network interface 2: ${eni2Id}" )
        cleanupTasks.add{
          print( "Deleting network interface 2: ${eni2Id}" )
          deleteNetworkInterface( new DeleteNetworkInterfaceRequest( networkInterfaceId: eni2Id ) )
        }

        // Run instance with pre-created network interfaces
        print( "Running instance 1" )
        String instance1Id = runInstances( new RunInstancesRequest(
            imageId: imageId,
            minCount: 1,
            maxCount: 1,
            placement: new Placement( availabilityZone ),
            networkInterfaces: [
              new InstanceNetworkInterfaceSpecification( deviceIndex: 0, networkInterfaceId: eni1Id ),
              new InstanceNetworkInterfaceSpecification( deviceIndex: 1, networkInterfaceId: eni2Id ),
            ]
        ) ).with {
          reservation?.instances?.getAt( 0 )?.with {
            assertThat( networkInterfaces?.size( ) == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size( )}" )
            println( networkInterfaces )
            instanceId
          }
        }
        print( "Created instance 1: ${instance1Id}" )
        cleanupTasks.add{
          print( "Terminating instance 1: ${instance1Id}" )
          terminateInstances( new TerminateInstancesRequest( instanceIds: [ instance1Id ] ))
        }

        // Run instance with network interfaces created on launch
        print( "Running instance 2" )
        String instance2Id = runInstances( new RunInstancesRequest(
            imageId: imageId,
            minCount: 1,
            maxCount: 1,
            placement: new Placement( availabilityZone ),
            networkInterfaces: [
                new InstanceNetworkInterfaceSpecification( deviceIndex: 0, subnetId: subnet1Id, groups: [ group1Id ], deleteOnTermination: true, description: 'network-interface-launch-1' ),
                new InstanceNetworkInterfaceSpecification( deviceIndex: 1, subnetId: subnet2Id, groups: [ group2Id ], deleteOnTermination: true, description: 'network-interface-launch-2'  ),
            ]
        ) ).with {
          reservation?.instances?.getAt( 0 )?.with {
            assertThat( networkInterfaces?.size( ) == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size( )}" )
            println( networkInterfaces )
            instanceId
          }
        }
        print( "Created instance 2: ${instance1Id}" )
        cleanupTasks.add{
          print( "Terminating instance 1: ${instance2Id}" )
          terminateInstances( new TerminateInstancesRequest( instanceIds: [ instance2Id ] ))
        }

        // Verify network interfaces created
        print( "Describing network interfaces filtering for ${instance2Id} attachment" )
        Collection<String> enis = describeNetworkInterfaces( new DescribeNetworkInterfacesRequest(
            filters: [ new Filter( name: 'attachment.instance-id', values: [ instance2Id ] ) ]
        ) ).with {
          assertThat( networkInterfaces?.size( ) == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size( )}" )
          networkInterfaces?.each { NetworkInterface networkInterface ->
            assertThat(
                networkInterface?.attachment?.instanceId == instance2Id,
                "Expected ${instance2Id} network interfaces attachment, but was: ${networkInterface?.attachment?.instanceId}" )
          }
          networkInterfaces*.networkInterfaceId
        }
        println( "Network interfaces created on run: ${enis}" )

        // Wait for instances to be available
        [ instance1Id, instance2Id ].each { String instanceId ->
          print( "Waiting for instance ${instanceId} to start" )
          ( 1..25 ).find{
            sleep 5000
            print( "Waiting for instance ${instanceId} to start, waited ${it*5}s" )
            describeInstances( new DescribeInstancesRequest(
                instanceIds: [ instanceId ],
                filters: [ new Filter( name: "instance-state-name", values: [ "running" ] ) ]
            ) ).with {
              reservations?.getAt( 0 )?.instances?.getAt( 0 )?.instanceId == instanceId
            }
          }
        }

        // Describe instances and verify attachment info
        [ instance1Id, instance2Id ].each { String instanceId ->
          print("Describing instance ${instanceId} to verify ENI attachments")
          describeInstances(new DescribeInstancesRequest(instanceIds: [instanceId])).with {
            assertThat(reservations?.size() == 1, "Expected 1 reservation, but was: ${reservations?.size()}")
            reservations?.getAt(0)?.with {
              assertThat(instances?.size() == 1, "Expected 1 instance, but was: ${instances?.size()}")
              instances?.getAt(0)?.with {
                assertThat(networkInterfaces?.size() == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size()}")
                println(networkInterfaces)
              }
            }
          }
        }

        // Describe network interfaces and verify attachment info
        print( "Describing network interfaces to verify ENI attachments" )
        describeNetworkInterfaces( new DescribeNetworkInterfacesRequest( networkInterfaceIds: [ eni1Id, eni2Id ] ) ).with {
          assertThat( networkInterfaces?.size( ) == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size( )}" )
          networkInterfaces?.each { NetworkInterface networkInterface ->
            assertThat(
                networkInterface?.attachment?.instanceId == instance1Id,
                "Expected ${instance1Id} network interfaces attachment, but was: ${networkInterface?.attachment?.instanceId}" )
          }
        }

        // Terminate instances
        print( "Terminating instances" )
        terminateInstances( new TerminateInstancesRequest( instanceIds: [ instance1Id, instance2Id ] ))

        // Wait for instances to terminate
        [ instance1Id, instance2Id ].each { String instanceId ->
          print( "Waiting for instance ${instanceId} to terminate" )
          ( 1..25 ).find{
            sleep 5000
            print( "Waiting for instance ${instanceId} to terminate, waited ${it*5}s" )
            describeInstances( new DescribeInstancesRequest(
                instanceIds: [ instanceId ],
                filters: [ new Filter( name: "instance-state-name", values: [ "terminated" ] ) ]
            ) ).with {
              reservations?.getAt( 0 )?.instances?.getAt( 0 )?.instanceId == instanceId
            }
          }
        }

        // Verify that network interfaces deleted as expected
        print("Describing instance 2 ${instance2Id} network interfaces ${enis} to verify deleted on termination")
        try {
          describeNetworkInterfaces( new DescribeNetworkInterfacesRequest( networkInterfaceIds: enis as List<String> ) ).with {
            assertThat( networkInterfaces?.size( ) == 0, "Expected 0 network interfaces, but was: ${networkInterfaces?.size( )}" )
          }
        } catch ( AmazonServiceException e ) {
          assertThat( e.errorCode == 'InvalidNetworkInterfaceID.NotFound', "Expected InvalidNetworkInterfaceID.NotFound error, but was: ${e.errorCode}")
          print( "Got expected exception: ${e}" )
        }

        // Check remaining network interfaces
        print( "Describing network interfaces to check unattached" )
        describeNetworkInterfaces( new DescribeNetworkInterfacesRequest( networkInterfaceIds: [ eni1Id, eni2Id ] ) ).with {
          assertThat( networkInterfaces?.size( ) == 2, "Expected 2 network interfaces, but was: ${networkInterfaces?.size( )}" )
          networkInterfaces?.each { NetworkInterface networkInterface ->
            assertThat(
                networkInterface?.attachment == null,
                "Expected no network interface attachment, but was: ${networkInterface?.attachment}" )
          }
        }

        void
      }

      print( "Test complete" )
    } finally {
      // Attempt to clean up anything we created
      cleanupTasks.reverseEach { Runnable cleanupTask ->
        try {
          cleanupTask.run()
        } catch ( NoSuchEntityException e ) {
          print( "Entity not found during cleanup." )
        } catch ( AmazonServiceException e ) {
          print( "Service error during cleanup; code: ${e.errorCode}, message: ${e.message}" )
        } catch ( Exception e ) {
          e.printStackTrace()
        }
      }
    }
  }
}
