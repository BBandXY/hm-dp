package com.hmdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigurationFilesTest {

    @Test
    void yamlFilesShouldBeValid() throws IOException {
        Yaml yaml = new Yaml();
        assertYaml(yaml, Paths.get("docker-compose.yml"));
        assertYaml(yaml, Paths.get("application-example.yaml"));
        assertYaml(yaml, Paths.get("src/main/resources/application.yaml"));
    }

    @Test
    void postmanCollectionShouldBeValidJson() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        assertNotNull(objectMapper.readTree(Paths.get("docs/hm-dp.postman_collection.json").toFile()));
    }

    private void assertYaml(Yaml yaml, Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            assertNotNull(yaml.load(inputStream), path + " should not be empty");
        }
    }
}
