package com.cs.receipt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SpringBootApplication
public class ReceiptApplication {

    @GetMapping("/")
    public String hello() {
        return "Hello World";
    }

    public static void main(String[] args) {
        SpringApplication.run(ReceiptApplication.class, args);
    }
}