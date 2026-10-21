package com.githubci.intelligence.configuration;

import java.util.Properties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import static org.junit.jupiter.api.Assertions.*;

class BackendBuildIdentityTest {
    @Test
    void logsPackagedBuildIdentityWithoutRequiringAnInfoEndpoint() {
        Properties values = new Properties();
        values.setProperty("artifact", "github-ci-intelligence-backend");
        values.setProperty("version", "0.1.0-SNAPSHOT");
        values.setProperty("time", "2026-10-21T22:00:00Z");
        var beans = new StaticListableBeanFactory();
        beans.addBean("buildProperties", new BuildProperties(values));
        String message = runAndCapture(beans.getBeanProvider(BuildProperties.class));
        assertEquals("Backend build identity: artifact=github-ci-intelligence-backend, "
                + "version=0.1.0-SNAPSHOT, builtAt=2026-10-21T22:00:00Z", message);
    }

    @Test
    void reportsMissingBuildMetadataExplicitlyForUnpackagedIdeLaunches() {
        var beans = new StaticListableBeanFactory();
        assertTrue(runAndCapture(beans.getBeanProvider(BuildProperties.class))
                .contains("Backend build identity unavailable"));
    }

    private String runAndCapture(ObjectProvider<BuildProperties> builds) {
        var logger = (Logger) LoggerFactory.getLogger(BackendBuildIdentity.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            new BackendBuildIdentity(builds).run(null);
            assertEquals(1, appender.list.size());
            assertNull(appender.list.getFirst().getThrowableProxy());
            return appender.list.getFirst().getFormattedMessage();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
