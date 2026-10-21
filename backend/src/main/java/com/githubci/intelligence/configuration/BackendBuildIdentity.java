package com.githubci.intelligence.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class BackendBuildIdentity implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BackendBuildIdentity.class);
    private final ObjectProvider<BuildProperties> builds;

    public BackendBuildIdentity(ObjectProvider<BuildProperties> builds) {
        this.builds = builds;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        BuildProperties build = builds.getIfAvailable();
        if (build == null) {
            log.warn("Backend build identity unavailable; generate META-INF/build-info.properties with Maven package");
            return;
        }
        log.info("Backend build identity: artifact={}, version={}, builtAt={}",
                build.getArtifact(), build.getVersion(), build.getTime());
    }
}
