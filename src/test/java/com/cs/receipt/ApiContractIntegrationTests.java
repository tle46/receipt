package com.cs.receipt;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class ApiContractIntegrationTests {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void recordResponsesAndSharedItemRequestsPreserveTheJsonContract() throws Exception {
        var userResult = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"contract_owner\",\"email\":\"contract-owner@example.com\",\"password\":\"a strong test password\",\"displayName\":\"contract-owner\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.username").value("contract_owner"))
                .andExpect(jsonPath("$.user.displayName").value("contract-owner"))
                .andExpect(jsonPath("$.user.status").value("ACTIVE")).andReturn();
        Number userId = JsonPath.read(userResult.getResponse().getContentAsString(), "$.user.id");
        String access = JsonPath.read(userResult.getResponse().getContentAsString(), "$.accessToken");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(get("/").header("Authorization", "Bearer " + access)).build();
        var created = mvc.perform(post("/api/receipts").param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"Cafe\",\"currency\":\"USD\",\"discount\":0,\"tax\":0,\"fee\":0,\"tip\":0,\"total\":10,\"items\":[{\"name\":\"Dinner\",\"quantity\":1,\"unitPrice\":10,\"total\":10}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value(userId.intValue()))
                .andExpect(jsonPath("$.subtotal").value(10)).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].allocations").isArray())
                .andExpect(jsonPath("$.participants[0].userId").value(userId.intValue()))
                .andExpect(jsonPath("$.adjustmentAllocations").isArray()).andReturn();
        Number receiptId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String path = "/api/receipts/" + receiptId;
        var itemResult = mvc.perform(post(path + "/items").param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dessert\",\"quantity\":1,\"unitPrice\":4}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.total").value(4)).andReturn();
        Number itemId = JsonPath.read(itemResult.getResponse().getContentAsString(), "$.id");
        mvc.perform(put(path + "/items/" + itemId).param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Two desserts\",\"quantity\":2,\"unitPrice\":4}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Two desserts"))
                .andExpect(jsonPath("$.total").value(8));
        mvc.perform(get(path).param("userId", userId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(18))
                .andExpect(jsonPath("$.items[?(@.name == 'Two desserts')]").isNotEmpty());
        mvc.perform(post(path + "/items").param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
        mvc.perform(put(path + "/items/" + itemId).param("userId", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
    }
}
