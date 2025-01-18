package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.codedeploy.InstanceTagSet;
import software.amazon.awscdk.services.codedeploy.ServerApplication;
import software.amazon.awscdk.services.codedeploy.ServerDeploymentConfig;
import software.amazon.awscdk.services.codedeploy.ServerDeploymentGroup;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.elasticache.CfnCacheCluster;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AwsCdkStack extends Stack {
    private final String PUBLIC_SUBNET = "PublicSubnet";
    private final String PRIVATE_SUBNET = "PrivateSubnet";

    public AwsCdkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String ec2KeyName = "nuvilab-task-key";
        var keyPair = KeyPair.fromKeyPairName(this, "key", ec2KeyName);

        Vpc vpc = Vpc.Builder.create(this, "nuvilab_ms2709_task_vpc")
                .maxAzs(2)  // 가용 영역
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .subnetType(SubnetType.PUBLIC) // 퍼블릭 서브넷
                                .name("PublicSubnet")
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .subnetType(SubnetType.PRIVATE_ISOLATED) //
                                .name("PrivateSubnet")
                                .cidrMask(24)
                                .build()
                ))
                .build();

        // Bastion Host 보안 그룹 (외부에서 SSH 접근 허용)
        SecurityGroup bastionSecurityGroup = SecurityGroup.Builder.create(this, "BastionSG")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();
        bastionSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(22), "Allow SSH access");

        Instance bastionInstance = Instance.Builder.create(this, "BastionInstance")
                .instanceType(InstanceType.of(InstanceClass.T2, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build()) // Public 서브넷 지정
                .keyPair(keyPair)
                .securityGroup(bastionSecurityGroup)
                .associatePublicIpAddress(true)  // 퍼블릭 IP 할당
                .build();

        var webServerSecurityGroup = SecurityGroup.Builder.create(this, "nuvilab_ms2709_task_EC2SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        webServerSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "HTTP Access");
        webServerSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS Access");
        webServerSecurityGroup.addIngressRule(bastionSecurityGroup, Port.tcp(22), "Allow Bastion Host to connect to EC2");

        var ec2InitCommand = UserData.forLinux();
        ec2InitCommand.addCommands(
                "sudo yum update",
                "sudo yum install ruby -y",
                "sudo gem update --system",
                "sudo yum install wget",
                "wget https://aws-codedeploy-ap-northeast-2.s3.ap-northeast-2.amazonaws.com/latest/install",
                "chmod +x ./install",
                "sudo ./install auto > /tmp/logfile",
                "sudo service codedeploy-agent status",
                "wget https://packages.adoptium.net/artifactory/rpm/amazonlinux/2/x86_64/Packages/temurin-17-jdk-17.0.7.0.0.7-1.x86_64.rpm",
                "sudo yum localinstall -y temurin-17-jdk-17.0.7.0.0.7-1.x86_64.rpm",
                "java -version",
                "sudo yum -y install tzdata",
                "sudo timedatectl set-timezone Asia/Seoul",
                "timedatectl",
                "date"
        );

        Role ec2Role = Role.Builder.create(this, "EC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .description("Role for EC2 instance to access AWS services")
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FullAccess")
                ))
                .build();

        var devEc2 = Instance.Builder.create(this, "nuvilab_task_dev_instance")
                .instanceType(InstanceType.of(InstanceClass.T2, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())
                .securityGroup(webServerSecurityGroup)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build()) // Public 서브넷 지정
                .role(ec2Role)
                .keyPair(keyPair)
                .userData(ec2InitCommand)
                .associatePublicIpAddress(true) //퍼블릭 IP 자동할당
                .build();
        Tags.of(devEc2).add("ec2-tag-key", "dev-ec2-tag-value");


        var prodEc2 = Instance.Builder.create(this, "nuvilab_task_prod_instance")
                .instanceType(InstanceType.of(InstanceClass.T2, InstanceSize.MICRO))
                .machineImage(MachineImage.latestAmazonLinux2())
                .securityGroup(webServerSecurityGroup)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build()) // Public 서브넷 지정
                .role(ec2Role)
                .keyPair(keyPair)
                .userData(ec2InitCommand)
                .associatePublicIpAddress(true) //퍼블릭 IP 자동할당
                .build();
        Tags.of(prodEc2).add("ec2-tag-prod-key", "ec2-tag-value");


        Role deploymentRole = Role.Builder.create(this, "CodeDeployRole")
                .assumedBy(new ServicePrincipal("codedeploy.amazonaws.com")) // CodeDeploy 서비스에 역할 위임 허용
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployFullAccess")
                ))
                .build();

        // CodeDeploy 애플리케이션 생성
        ServerApplication codeDeployApp = ServerApplication.Builder.create(this, "CodeDeployApplication")
                .applicationName("nuvilab_ms2709_task_app")
                .build();
        // Deployment Group 생성 (dev)
        ServerDeploymentGroup.Builder.create(this, "CodeDeployDeploymentGroup")
                .application(codeDeployApp) // 앞에서 생성한 애플리케이션 지정
                .deploymentGroupName("nuvilab_ms2709_task_app_deploy_group_name")
                .role(deploymentRole)
                .ec2InstanceTags(new InstanceTagSet(
                        Map.of(
                                "ec2-tag-key", List.of("dev-ec2-tag-value") // EC2 태그 조건으로 타겟 설정
                        )
                ))
                .deploymentConfig(ServerDeploymentConfig.ALL_AT_ONCE) // 배포 설정 (한 번에 배포)
                .build();

        // Deployment Group 생성 (prod)
        ServerDeploymentGroup.Builder.create(this, "CodeDeployDeploymentGroupProd")
                .application(codeDeployApp) // 앞에서 생성한 애플리케이션 지정
                .deploymentGroupName("nuvilab_ms2709_task_app_deploy_group_name_prod")
                .role(deploymentRole)
                .ec2InstanceTags(new InstanceTagSet(
                        Map.of(
                                "ec2-tag-prod-key", List.of("ec2-tag-value") // EC2 태그 조건으로 타겟 설정
                        )
                ))
                .deploymentConfig(ServerDeploymentConfig.ALL_AT_ONCE) // 배포 설정 (한 번에 배포)
                .build();

        Bucket bucket = Bucket.Builder.create(this, "nuvilab_task_bucket_id").bucketName("nuvilab.task.bucket-dev").removalPolicy(RemovalPolicy.DESTROY).build();

        var rdsSecurityGroup = SecurityGroup.Builder.create(this, "nuvilab_ms2709_task_RDS_SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();
        rdsSecurityGroup.addIngressRule(bastionSecurityGroup, Port.tcp(3306), "Allow Bastion Host to connect to RDS");
        rdsSecurityGroup.addIngressRule(webServerSecurityGroup, Port.tcp(3306), "Allow Server Host to connect to RDS");

        DatabaseInstance rdsInstance = DatabaseInstance.Builder.create(this, "MyRDSInstance")
                .engine(DatabaseInstanceEngine.mysql(MySqlInstanceEngineProps.builder().version(MysqlEngineVersion.VER_8_4_3).build()))
                .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO))
                .allocatedStorage(20)
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build()) // Private 서브넷 지정
                .credentials(Credentials.fromGeneratedSecret("admin")) // RDS 루트 계정 비밀번호 생성
                .multiAz(false) // Multi-AZ 비활성화 (테스트 환경에서는 비용 절감을 위해 비활성화 가능)
                .securityGroups(List.of(rdsSecurityGroup))
                .removalPolicy(RemovalPolicy.DESTROY) // 스택 삭제 시 RDS 인스턴스 삭제
                .build();

        SecurityGroup elasticacheSecurityGroup = SecurityGroup.Builder.create(this, "ElastiCacheSafetyGroup")
                .vpc(vpc)
                .description("Security group for ElastiCache cluster")
                .allowAllOutbound(true)
                .build();
        elasticacheSecurityGroup.addIngressRule(bastionSecurityGroup, Port.tcp(6379), "Allow access to Redis from Bastion Host");
        elasticacheSecurityGroup.addIngressRule(webServerSecurityGroup, Port.tcp(6379), "Allow access to Redis from Server");

        // 서브넷 그룹 생성 (Private Subnets)
        // 서브넷 ID 디버깅 출력
        List<String> subnetIds = vpc.getIsolatedSubnets().stream()
                .map(subnet -> subnet.getSubnetId()) // 서브넷 ID 가져오기
                .filter(subnetId -> subnetId != null && !subnetId.isEmpty()) // Null 및 빈 값 제거
                .toList();
        System.out.println("전달된 Subnet IDs: " + subnetIds);

        CfnSubnetGroup elasticacheSubnetGroup = CfnSubnetGroup.Builder.create(this, "ElastiCacheSubnetGroup")
                .cacheSubnetGroupName("my-elasticache-subnet-group")
                .description("Subnet group for ElastiCache")
                .subnetIds(vpc.getIsolatedSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()))
                .build();
        CfnCacheCluster cacheCluster = CfnCacheCluster.Builder.create(this, "RedisCluster")
                .cacheNodeType("cache.t2.micro")  // 노드 타입
                .engine("redis")  // Redis 엔진 사용
                .numCacheNodes(1) // 노드 수
                .cacheSubnetGroupName(elasticacheSubnetGroup.getCacheSubnetGroupName()) // 서브넷 그룹 연결
                .vpcSecurityGroupIds(List.of(elasticacheSecurityGroup.getSecurityGroupId())) // 보안 그룹 연결
                .cacheSubnetGroupName("my-elasticache-subnet-group")
                .clusterName("my-redis-cluster") // 클러스터 이름
                .build();

        CfnOutput.Builder.create(this, "RedisClusterEndpoint")
                .value(cacheCluster.getAttrRedisEndpointAddress())
                .description("Redis Cluster Endpoint")
                .build();

        //bastion public dns
        CfnOutput.Builder.create(this, "bastionInstancePublic DNS Name")
                .value(bastionInstance.getInstancePublicDnsName())
                .description("bastionInstancePublic DNS Name")
                .build();

        //dev public dns
        CfnOutput.Builder.create(this, "dev-WebServerPublic DNS Name")
                .value(devEc2.getInstancePublicDnsName())
                .description("dev-WebServerPublic DNS Name")
                .build();

        //dev private ip
        CfnOutput.Builder.create(this, "dev-WebServer Private IP")
                .value(devEc2.getInstancePrivateIp())  // 퍼블릭 IP를 출력
                .description("dev-WebServer Private IP")
                .build();

        //prod public dns
        CfnOutput.Builder.create(this, "prod-WebServerPublic DNS Name")
                .value(prodEc2.getInstancePublicDnsName())
                .description("prod-WebServerPublic DNS Name")
                .build();

        //prod private ip
        CfnOutput.Builder.create(this, "prod-WebServer Private IP")
                .value(prodEc2.getInstancePrivateIp())  // 퍼블릭 IP를 출력
                .description("prod-WebServer Private IP")
                .build();


        // 버킷 Name 출력
        CfnOutput.Builder.create(this, "BucketName")
                .description("ARN for the S3 Bucket Name")
                .value(bucket.getBucketName())
                .build();

        // 버킷 URL 출력
        CfnOutput.Builder.create(this, "BucketUrl")
                .description("URL for the S3 Bucket")
                .value(bucket.getBucketWebsiteUrl()) // 버킷의 정적 웹사이트 URL
                .build();

        // RDS 엔드포인트 주소 출력
        CfnOutput.Builder.create(this, "RdsEndpointAddress")
                .value(rdsInstance.getDbInstanceEndpointAddress())
                .description("Endpoint address for the RDS instance")
                .build();
    }
}
