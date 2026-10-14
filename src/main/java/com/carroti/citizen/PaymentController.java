package com.carroti.citizen;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/payments")
@ConditionalOnExpression("'${app.service}' == 'payment' or '${app.service}' == 'all'")
public class PaymentController {
 private final JdbcTemplate jdbc;
 private final DemoIdentity identity;
 public PaymentController(JdbcTemplate jdbc,DemoIdentity identity) { this.jdbc=jdbc; this.identity=identity; }
 public record Pay(@NotBlank String orderId) {}
 @PostMapping("/simulate") @Transactional
 public Map<String,Object> pay(@RequestHeader(value="Authorization",required=false) String header,@Valid @RequestBody Pay r) {
  String memberId=identity.id(header);
  var orders=jdbc.queryForList("SELECT * FROM citizen_orders WHERE id=? AND member_id=? FOR UPDATE",r.orderId(),memberId);
  if(orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"신청 내역을 찾을 수 없습니다.");
  // Lock + UNIQUE(order_id) make repeated payment requests idempotent.
  var payments=jdbc.queryForList("SELECT * FROM citizen_payments WHERE order_id=?",r.orderId());
  if(!payments.isEmpty()) return payments.get(0);
  int amount=((Number)orders.get(0).get("fee")).intValue();
  if(amount==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"수수료가 없는 민원입니다.");
  String id=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO citizen_payments(id,order_id,member_id,amount) VALUES (?,?,?,?)",id,r.orderId(),memberId,amount);
  jdbc.update("UPDATE citizen_orders SET status='RECEIVED' WHERE id=?",r.orderId());
  return jdbc.queryForMap("SELECT * FROM citizen_payments WHERE id=?",id);
 }
 @GetMapping("/{orderId}")
 public Map<String,Object> receipt(@RequestHeader(value="Authorization",required=false) String header,@PathVariable String orderId) {
  var rows=jdbc.queryForList("SELECT * FROM citizen_payments WHERE order_id=? AND member_id=?",orderId,identity.id(header));
  if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"결제 내역이 없습니다.");
  return rows.get(0);
 }
}
