package com.parlour.management;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
class MySqlIntegrationTest extends BookingIntegrationTest {
 @DynamicPropertySource
 static void mysql(DynamicPropertyRegistry registry) {
  registry.add("spring.datasource.url",()->System.getenv("MYSQL_TEST_URL"));
  registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
  registry.add("spring.datasource.username",()->System.getenv("MYSQL_TEST_USER"));
  registry.add("spring.datasource.password",()->System.getenv("MYSQL_TEST_PASSWORD"));
 }
}
