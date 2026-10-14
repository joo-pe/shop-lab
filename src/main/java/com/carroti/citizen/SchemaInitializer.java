package com.carroti.citizen;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class SchemaInitializer implements ApplicationRunner {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 public SchemaInitializer(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc=jdbc; this.tx=tx; }
 @Override public void run(ApplicationArguments args) {
  tx.executeWithoutResult(status -> {
   String product=jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
   // Serialize DDL when six PostgreSQL clients start together.
   if ("PostgreSQL".equals(product)) jdbc.execute("SELECT pg_advisory_xact_lock(812346)");
   jdbc.execute("""
    CREATE TABLE IF NOT EXISTS citizen_members (
      id VARCHAR(36) PRIMARY KEY, name VARCHAR(40) NOT NULL,
      token_hash VARCHAR(64) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
    """);
   jdbc.execute("""
    CREATE TABLE IF NOT EXISTS citizen_orders (
      id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL REFERENCES citizen_members(id),
      category VARCHAR(30) NOT NULL, title VARCHAR(100) NOT NULL, content VARCHAR(2000) NOT NULL,
      fee INTEGER NOT NULL, status VARCHAR(30) NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
    """);
   jdbc.execute("""
    CREATE TABLE IF NOT EXISTS citizen_payments (
      id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL UNIQUE REFERENCES citizen_orders(id),
      member_id VARCHAR(36) NOT NULL REFERENCES citizen_members(id), amount INTEGER NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
    """);
  });
 }
}
