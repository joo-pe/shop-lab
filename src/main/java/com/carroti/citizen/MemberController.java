package com.carroti.citizen;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/members")
@ConditionalOnExpression("'${app.service}' == 'member' or '${app.service}' == 'all'")
public class MemberController {
 private final JdbcTemplate jdbc;
 private final DemoIdentity identity;
 public MemberController(JdbcTemplate jdbc,DemoIdentity identity) { this.jdbc=jdbc; this.identity=identity; }
 public record CreateMember(@NotBlank @Size(max=40) String name) {}
 @PostMapping("/demo") @ResponseStatus(HttpStatus.CREATED)
 public Map<String,Object> create(@Valid @RequestBody CreateMember r) {
  String id=UUID.randomUUID().toString();
  String token=UUID.randomUUID().toString()+UUID.randomUUID();
  jdbc.update("INSERT INTO citizen_members(id,name,token_hash) VALUES (?,?,?)",id,r.name().trim(),DemoIdentity.hash(token));
  return Map.of("id",id,"name",r.name().trim(),"token",token);
 }
 @GetMapping("/me")
 public Map<String,Object> me(@RequestHeader(value="Authorization",required=false) String header) { return identity.member(header); }
}
