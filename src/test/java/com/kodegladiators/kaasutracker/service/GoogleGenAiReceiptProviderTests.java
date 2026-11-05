package com.kodegladiators.kaasutracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GoogleGenAiReceiptProviderTests {

    private final ObjectMapper strictMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void parsesPartialStructuredResponseWithoutInventingUnknownFields() {
        var response = GoogleGenAiReceiptProvider.parseResponse(
                strictMapper,
                """
                        {"merchant":"Shop","totalAmount":12.5,"uncertainFields":["receiptDate"]}
                        """);

        assertEquals(new BigDecimal("12.5"), response.totalAmount());
        assertNull(response.receiptDate());
        assertNull(response.currency());
    }

    @Test
    void rejectsMalformedOrUnexpectedStructuredResponse() {
        assertThrows(IllegalStateException.class, () -> GoogleGenAiReceiptProvider.parseResponse(
                strictMapper, "{\"totalAmount\":"));
        assertThrows(IllegalStateException.class, () -> GoogleGenAiReceiptProvider.parseResponse(
                strictMapper, "{\"unexpected\":\"value\"}"));
    }
}
