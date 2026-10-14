package com.carroti.citizen;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/orders")
@ConditionalOnExpression("'${app.service}' == 'order' or '${app.service}' == 'all'")
public class OrderController {
 private final JdbcTemplate jdbc;
 private final DemoIdentity identity;
 public OrderController(JdbcTemplate jdbc,DemoIdentity identity) { this.jdbc=jdbc; this.identity=identity; }
 public record CreateOrder(@NotBlank @Pattern(regexp="LIVING|TRAFFIC|ENVIRONMENT|CERTIFICATE") String category,
  @NotBlank @Size(max=100) String title,@NotBlank @Size(max=2000) String content) {}
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public Map<String,Object> create(@RequestHeader(value="Authorization",required=false) String header,@Valid @RequestBody CreateOrder r) {
  String memberId=identity.id(header),id=UUID.randomUUID().toString();
  int fee="CERTIFICATE".equals(r.category())?1000:0;
  jdbc.update("INSERT INTO citizen_orders(id,member_id,category,title,content,fee,status) VALUES (?,?,?,?,?,?,?)",
   id,memberId,r.category(),r.title().trim(),r.content().trim(),fee,fee>0?"PAYMENT_PENDING":"RECEIVED");
  return detail(header,id);
 }
 @GetMapping
 public List<Map<String,Object>> list(@RequestHeader(value="Authorization",required=false) String header) {
  return jdbc.queryForList("SELECT * FROM citizen_orders WHERE member_id=? ORDER BY created_at DESC LIMIT 100",identity.id(header));
 }
 @GetMapping("/{id}")
 public Map<String,Object> detail(@RequestHeader(value="Authorization",required=false) String header,@PathVariable String id) {
  var rows=jdbc.queryForList("SELECT * FROM citizen_orders WHERE id=? AND member_id=?",id,identity.id(header));
  if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"신청 내역을 찾을 수 없습니다.");
  return rows.get(0);
 }
}
