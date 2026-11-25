package com.jobscheduler.worker.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.jobscheduler.worker.WorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Docker client over the daemon's unix socket. Socket access is root-equivalent on the host, so
 * only the worker process gets it; job containers never do.
 */
@Configuration
@Profile("worker")
public class DockerConfig {

    @Bean(destroyMethod = "close")
    public DockerClient dockerClient(WorkerProperties props) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(props.docker().host())
                .withApiVersion(props.docker().apiVersion())
                .build();
        // Each slot holds up to two long-lived requests (wait + follow logs), plus short calls.
        DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(props.concurrency() * 3 + 10)
                .connectionTimeout(Duration.ofSeconds(30))
                .build();
        return DockerClientImpl.getInstance(config, http);
    }
}
