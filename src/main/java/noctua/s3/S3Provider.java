package noctua.s3;

import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class S3Provider {

    private final AwsCredentialsProvider credentials;

    public S3Provider() {
        this.credentials = DefaultCredentialsProvider.create();
    }

    public S3Client getS3Client() {

        Dotenv dotenv = Dotenv.load();

        AwsSessionCredentials credenciais =
                AwsSessionCredentials.create(
                        dotenv.get("AWS_ACCESS_KEY_ID"),
                        dotenv.get("AWS_SECRET_ACCESS_KEY"),
                        dotenv.get("AWS_SESSION_TOKEN")
                );

        return S3Client.builder()
                .region(Region.of(dotenv.get("AWS_REGION")))
                .credentialsProvider(
                        StaticCredentialsProvider.create(credenciais)
                )
                .build();
    }
}
