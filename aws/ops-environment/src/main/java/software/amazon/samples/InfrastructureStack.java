package software.amazon.samples;

import software.amazon.awscdk.*;
import software.amazon.awscdk.assets.Asset;
import software.amazon.awscdk.assets.AssetPackaging;
import software.amazon.awscdk.assets.AssetProps;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroupProps;
import software.amazon.awscdk.services.autoscaling.UpdateType;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;

import java.util.Arrays;

public class InfrastructureStack extends Stack {

    public static final String DEMO_JAR = "helidon-quickstart-se.jar";
    public static final String DEMO_JAR_PATH = "./../../target/" + DEMO_JAR;
    private static final String DEMO_KEY = "demo_key";

    public InfrastructureStack(final App parent, final String name) {
        this(parent, name, null);
    }

    public InfrastructureStack(final App parent, final String name, final StackProps props) {
        super(parent, name, props);

        //  jar (the Asset construct takes the local file and stores it in S3)
        Asset demoJar = new Asset(this, "DemoJar", AssetProps.builder()
            .withPath(DEMO_JAR_PATH)
            .withPackaging(AssetPackaging.File)
            .build());

        // create a vpc (software defined network)
        VpcNetwork vpc = new VpcNetwork(this, "DemoVPC", VpcNetworkProps.builder().build());


        // create an autoscaling group and place it within the vpc
        AutoScalingGroup asg = new AutoScalingGroup(this, "DemoAutoScale",
            AutoScalingGroupProps.builder()
                .withVpc(vpc)
                    .withAssociatePublicIpAddress(true)
                    //you can connect to server via ssh with your key
                    .withKeyName(DEMO_KEY)
                .withInstanceType(new InstanceTypePair(InstanceClass.Burstable2, InstanceSize.Micro))
                .withMachineImage(new AmazonLinuxImage(AmazonLinuxImageProps.builder()
                    .withGeneration(AmazonLinuxGeneration.AmazonLinux2)
                    .build()))
                    .withMinCapacity(1).withMaxCapacity(2)
                .withUpdateType(UpdateType.RollingUpdate)
                    .withVpcSubnets(SubnetSelection.builder().withSubnetType(SubnetType.Public).build())
                .build());

        // grant our ec2 instance roles the permission to read the petclinic jar from s3
        demoJar.grantRead(asg.getRole());

        // install the petclinic application on the instances in our autoscaling group
        asg.addUserData(
            "amazon-linux-extras enable corretto8",
            "yum -y update",
            "yum -y install java-1.8.0-amazon-corretto",
            "# Download and run the demo jar",
            String.format("aws s3 cp s3://%s/%s /tmp/%s", demoJar.getS3BucketName(), demoJar.getS3ObjectKey(), DEMO_JAR),
            String.format("java -jar /tmp/%s &>> /tmp/demo.log", DEMO_JAR)
        );

        // create an internet facing load balancer and place it within the the vpc
        ApplicationLoadBalancer alb = new ApplicationLoadBalancer(this, "DemoLB",
            ApplicationLoadBalancerProps.builder()
                .withVpc(vpc)
                .withInternetFacing(true)
                .build());

        ApplicationListener listener = new ApplicationListener(this, "DemoListener",
            ApplicationListenerProps.builder()
                .withPort(80)
                .withOpen(true)
                .withLoadBalancer(alb)
                .build());

        // connect the autoscaling group running petclinic with the load balancer
        listener.addTargets("DemoFleet", AddApplicationTargetsProps.builder()
            .withTargets(Arrays.asList(asg))
                .withProtocol(ApplicationProtocol.Http)
            .withPort(8080)
            .build());

        // output the load balancer url to make tracking down our applications new address easier
        CfnOutput output = new CfnOutput(this, "DemoUrl", CfnOutputProps.builder()
            .withValue(alb.getDnsName())
            .build());
    }
}

