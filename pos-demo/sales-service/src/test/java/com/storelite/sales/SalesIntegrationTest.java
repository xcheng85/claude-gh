package com.storelite.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storelite.sales.alert.AlertRepository;
import com.storelite.sales.inventory.CatalogProduct;
import com.storelite.sales.inventory.InventoryClient;
import com.storelite.sales.messaging.LowStock;
import com.storelite.sales.messaging.RabbitConfig;
import com.storelite.sales.messaging.SaleCompleted;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// A small pool with a short timeout turns a connection leak into a fast, visible failure.
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=2000"})
@AutoConfigureMockMvc
@Testcontainers
class SalesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired TopicExchange posEvents;
    @Autowired AlertRepository alerts;

    /** inventory-service is stubbed; its side of the contract is covered by its own tests. */
    @MockitoBean InventoryClient inventory;

    @BeforeEach
    void stubCatalog() {
        given(inventory.catalog()).willReturn(Map.of(
                "MILK-1L", new CatalogProduct("MILK-1L", "Whole Milk 1L", 249),
                "EGGS-12", new CatalogProduct("EGGS-12", "Large Eggs (12)", 449)));
    }

    @Test
    void checkoutStoresSaleAndPublishesSaleCompleted() throws Exception {
        Queue saleQueue = new AnonymousQueue();
        amqpAdmin.declareQueue(saleQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(saleQueue).to(posEvents).with(RabbitConfig.SALE_COMPLETED_KEY));

        JsonNode summaryBefore = summary();

        String body = mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tenderType": "CARD", "lines": [{"sku": "MILK-1L", "quantity": 2}, {"sku": "EGGS-12", "quantity": 1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents").value(947))
                .andExpect(jsonPath("$.lines[0].name").value("Whole Milk 1L"))
                .andReturn().getResponse().getContentAsString();
        String saleId = json.readTree(body).get("id").asText();

        SaleCompleted event = rabbitTemplate.receiveAndConvert(saleQueue.getName(), 10_000,
                new ParameterizedTypeReference<SaleCompleted>() {});
        assertThat(event).isNotNull();
        assertThat(event.saleId()).hasToString(saleId);
        assertThat(event.lines()).containsExactly(
                new SaleCompleted.Line("MILK-1L", 2), new SaleCompleted.Line("EGGS-12", 1));

        JsonNode summaryAfter = summary();
        assertThat(summaryAfter.get("count").asLong()).isEqualTo(summaryBefore.get("count").asLong() + 1);
        assertThat(summaryAfter.get("totalCents").asLong()).isEqualTo(summaryBefore.get("totalCents").asLong() + 947);

        mvc.perform(get("/api/sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + saleId + "')].lines.length()").value(2));
    }

    @Test
    void listingSalesRepeatedlyDoesNotLeakConnections() throws Exception {
        // The dashboard polls this endpoint; the line query only runs when the day has sales.
        mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tenderType": "CASH", "lines": [{"sku": "MILK-1L", "quantity": 1}]}"""))
                .andExpect(status().isCreated());

        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/sales")).andExpect(status().isOk());
        }
    }

    @Test
    void unknownSkuIsUnprocessable() throws Exception {
        mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tenderType": "CASH", "lines": [{"sku": "NOPE", "quantity": 1}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void emptyBasketIsBadRequest() throws Exception {
        mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content("""
                        {"tenderType": "CASH", "lines": []}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lowStockEventBecomesAlertOnce() {
        LowStock event = new LowStock(UUID.randomUUID(), "EGGS-12", 3, 5, OffsetDateTime.now());

        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.STOCK_LOW_KEY, event);
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.STOCK_LOW_KEY, event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(alerts.latest(20)).filteredOn(a -> a.sku().equals("EGGS-12")).hasSize(1));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(alerts.latest(20)).filteredOn(a -> a.sku().equals("EGGS-12")).hasSize(1));
    }

    private JsonNode summary() throws Exception {
        return json.readTree(mvc.perform(get("/api/sales/summary")).andReturn().getResponse().getContentAsString());
    }
}
