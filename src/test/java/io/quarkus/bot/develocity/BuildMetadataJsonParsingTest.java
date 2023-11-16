package io.quarkus.bot.develocity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.bot.develocity.InjectBuildScansAction.BuildScanStatuses;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class BuildMetadataJsonParsingTest {

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testParsing() throws StreamReadException, DatabindException, IOException {
        BuildScanStatuses statuses = objectMapper.readValue(
                BuildMetadataJsonParsingTest.class.getClassLoader().getResourceAsStream("develocity/build-metadata.json"),
                BuildScanStatuses.class);

        assertThat(statuses.builds.stream().map(s -> s.jobName).collect(Collectors.toList()))
                .containsExactly("Initial JDK 11 Build", "Maven Tests - JDK 11", "Maven Tests - JDK 11 Windows",
                        "Gradle Tests - JDK 11", "Gradle Tests - JDK 11 Windows", "Devtools Tests - JDK 11",
                        "Devtools Tests - JDK 17", "Devtools Tests - JDK 11 Windows", "Kubernetes Tests - JDK 11",
                        "Kubernetes Tests - JDK 17", "Kubernetes Tests - JDK 11 Windows", "MicroProfile TCKs Tests",
                        "Native Tests - Amazon", "Native Tests - AWT, ImageIO and Java2D", "Native Tests - Cache",
                        "Native Tests - Data1", "Native Tests - Data2", "Native Tests - Data3", "Native Tests - Data4",
                        "Native Tests - Data5", "Native Tests - Data6", "Native Tests - Data7",
                        "Native Tests - DevTools Integration Tests", "Native Tests - gRPC", "Native Tests - HTTP",
                        "Native Tests - Main", "Native Tests - Messaging1", "Native Tests - Messaging2", "Native Tests - Misc1",
                        "Native Tests - Misc2", "Native Tests - Misc3", "Native Tests - Misc4", "Native Tests - Security1",
                        "Native Tests - Security2", "Native Tests - Security3", "Native Tests - Spring",
                        "Native Tests - Virtual Thread - Main", "Native Tests - Virtual Thread - Messaging",
                        "Native Tests - Windows - RESTEasy Jackson");
    }
}
