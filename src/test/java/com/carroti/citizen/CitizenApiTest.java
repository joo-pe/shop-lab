package com.carroti.citizen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CitizenApiTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 String member(String name) throws Exception {
  var response=mvc.perform(post("/api/members/demo").contentType("application/json").content("{\"name\":\""+name+"\"}"))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  return "Bearer "+json.readTree(response).get("token").asText();
 }
 JsonNode order(String token,String category) throws Exception {
  String body="{\"category\":\""+category+"\",\"title\":\"Test petition\",\"content\":\"Sample only\"}";
  return json.readTree(mvc.perform(post("/api/orders").header("Authorization",token).contentType("application/json").content(body))
   .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
 }
 @Test void freePetitionIsReceivedAndCannotBePaid() throws Exception {
  String token=member("Free");JsonNode o=order(token,"LIVING");
  assertThat(o.get("fee").asInt()).isZero();assertThat(o.get("status").asText()).isEqualTo("RECEIVED");
  mvc.perform(post("/api/payments/simulate").header("Authorization",token).contentType("application/json").content("{\"orderId\":\""+o.get("id").asText()+"\"}"))
   .andExpect(status().isConflict());
 }
 @Test void paymentIsIdempotentAndAmountIsServerControlled() throws Exception {
  String token=member("Paid");JsonNode o=order(token,"CERTIFICATE");String id=o.get("id").asText();
  assertThat(o.get("status").asText()).isEqualTo("PAYMENT_PENDING");
  String body="{\"orderId\":\""+id+"\",\"amount\":1}";
  var first=mvc.perform(post("/api/payments/simulate").header("Authorization",token).contentType("application/json").content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(1000)).andReturn().getResponse().getContentAsString();
  var second=mvc.perform(post("/api/payments/simulate").header("Authorization",token).contentType("application/json").content(body))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(json.readTree(first).get("id")).isEqualTo(json.readTree(second).get("id"));
  mvc.perform(get("/api/orders/"+id).header("Authorization",token)).andExpect(jsonPath("$.status").value("RECEIVED"));
 }
 @Test void anotherMemberCannotReadOrPayPetition() throws Exception {
  String owner=member("Owner"), other=member("Other");String id=order(owner,"CERTIFICATE").get("id").asText();
  mvc.perform(get("/api/orders/"+id).header("Authorization",other)).andExpect(status().isNotFound());
  mvc.perform(get("/api/orders").header("Authorization",other)).andExpect(content().json("[]"));
  mvc.perform(post("/api/payments/simulate").header("Authorization",other).contentType("application/json").content("{\"orderId\":\""+id+"\"}"))
   .andExpect(status().isNotFound());
 }
 @Test void unauthenticatedRequestsAreRejected() throws Exception {
  mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
 }
 @Test void invalidCategoryIsRejected() throws Exception {
  mvc.perform(post("/api/orders").header("Authorization",member("Invalid")).contentType("application/json")
   .content("{\"category\":\"INVALID\",\"title\":\"x\",\"content\":\"x\"}"))
   .andExpect(status().isBadRequest());
 }
}
