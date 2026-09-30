package com.cs.receipt;

import com.cs.receipt.controller.ReceiptController;
import com.cs.receipt.exception.ApiExceptionHandler;
import com.cs.receipt.exception.ForbiddenOperationException;
import com.cs.receipt.exception.ResourceNotFoundException;
import com.cs.receipt.service.ReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerWebTests {

    private ReceiptService receiptService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        receiptService = mock(ReceiptService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ReceiptController(receiptService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsNotFoundForAMissingReceipt() throws Exception {
        when(receiptService.getReceipt(99L, 1L))
                .thenThrow(new ResourceNotFoundException("Receipt not found"));

        mockMvc.perform(get("/api/receipts/99").param("userId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Receipt not found"))
                .andExpect(jsonPath("$.path").value("/api/receipts/99"));
    }

    @Test
    void returnsForbiddenWhenTheRequesterIsNotTheOwner() throws Exception {
        when(receiptService.getReceipt(10L, 2L))
                .thenThrow(new ForbiddenOperationException("Only the receipt owner can access this receipt"));

        mockMvc.perform(get("/api/receipts/10").param("userId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void returnsFieldErrorsForAnInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/api/receipts").param("userId", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.items").value("Items are required"));
    }
}
