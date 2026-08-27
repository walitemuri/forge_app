package dev.forge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest(
        properties = {
                "forge.grpc.port=0"
        }
)
class ControllerApplicationTests {

    @Test
    void contextLoads() {
    }
}