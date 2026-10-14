package com.carroti.citizen;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
@Component
public class DemoIdentity {
 private final JdbcTemplate jdbc;
 public DemoIdentity(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 public static String hash(String value) {
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
  catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
 public Map<String,Object> member(String header) {
  if (header==null || !header.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"체험 계정을 먼저 만들어 주세요.");
  var rows=jdbc.queryForList("SELECT id,name FROM citizen_members WHERE token_hash=?",hash(header.substring(7)));
  if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"체험 계정을 다시 만들어 주세요.");
  return rows.get(0);
 }
 public String id(String header) { return member(header).get("id").toString(); }
}
