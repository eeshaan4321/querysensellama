package com.querysense.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "querysense.mongo.demo.seed-on-startup=false")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
