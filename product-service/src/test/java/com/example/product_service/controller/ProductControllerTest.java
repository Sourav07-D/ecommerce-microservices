package com.example.product_service.controller;

import com.example.product_service.dto.ProductRequestDTO;
import com.example.product_service.dto.ProductResponseDTO;
import com.example.product_service.exception.GlobalExceptionHandler;
import com.example.product_service.exception.ProductNotFoundException;
import com.example.product_service.service.ProductService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(ProductController.class)
@Import(GlobalExceptionHandler.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;


    // Helper method to create a sample product response
    private ProductResponseDTO createResponse(Long id) {

        return new ProductResponseDTO(
                id,
                "Wireless Mouse",
                "Wireless mouse with USB receiver",
                new BigDecimal("999.00"),
                "Electronics",
                null,
                null
        );
    }


    // Helper method to create a valid product request
    private ProductRequestDTO createRequest() {

        ProductRequestDTO request = new ProductRequestDTO();

        request.setName("Wireless Mouse");
        request.setDescription("Wireless mouse with USB receiver");
        request.setPrice(new BigDecimal("999.00"));
        request.setCategory("Electronics");

        return request;
    }


    // 1. POST /api/products -> 201 CREATED
    @Test
    void createProduct_shouldReturn201() throws Exception {

        ProductResponseDTO response = createResponse(1L);

        when(productService.createProduct(any(ProductRequestDTO.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.price").value(999.00))
                .andExpect(jsonPath("$.category").value("Electronics"));

        verify(productService).createProduct(any(ProductRequestDTO.class));
    }


    // 2. GET /api/products -> 200 OK
    @Test
    void getAllProducts_shouldReturn200() throws Exception {

        List<ProductResponseDTO> products = List.of(
                createResponse(1L),
                new ProductResponseDTO(
                        2L,
                        "Mechanical Keyboard",
                        "Mechanical keyboard for everyday typing",
                        new BigDecimal("2499.00"),
                        "Electronics",
                        null,
                        null
                )
        );

        when(productService.getAllProducts()).thenReturn(products);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Wireless Mouse"))
                .andExpect(jsonPath("$[1].name").value("Mechanical Keyboard"));

        verify(productService).getAllProducts();
    }


    // 3. GET /api/products/{id} -> 200 OK
    @Test
    void getProductById_shouldReturn200() throws Exception {

        when(productService.getProductById(1L))
                .thenReturn(createResponse(1L));

        mockMvc.perform(get("/api/products/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.price").value(999.00));

        verify(productService).getProductById(1L);
    }


    // 4. GET /api/products/{id} -> 404 NOT FOUND
    @Test
    void getProductById_shouldReturn404WhenProductDoesNotExist()
            throws Exception {

        when(productService.getProductById(999L))
                .thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(get("/api/products/{id}", 999L))
                .andExpect(status().isNotFound());

        verify(productService).getProductById(999L);
    }


    // 5. POST /api/products with a blank name -> 400 BAD REQUEST
    @Test
    void createProduct_shouldReturn400WhenNameIsBlank() throws Exception {

        ProductRequestDTO request = createRequest();
        request.setName(" ");

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }


    // 6. POST /api/products with a negative price -> 400 BAD REQUEST
    @Test
    void createProduct_shouldReturn400WhenPriceIsNegative()
            throws Exception {

        ProductRequestDTO request = createRequest();
        request.setPrice(new BigDecimal("-10.00"));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }


    // 7. PUT /api/products/{id} -> 200 OK
    @Test
    void updateProduct_shouldReturn200() throws Exception {

        ProductResponseDTO response = createResponse(1L);

        when(productService.updateProduct(
                eq(1L),
                any(ProductRequestDTO.class)
        )).thenReturn(response);

        mockMvc.perform(put("/api/products/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.price").value(999.00));

        verify(productService).updateProduct(
                eq(1L),
                any(ProductRequestDTO.class)
        );
    }


    // 8. DELETE /api/products/{id} -> 204 NO CONTENT
    @Test
    void deleteProduct_shouldReturn204() throws Exception {

        mockMvc.perform(delete("/api/products/{id}", 1L))
                .andExpect(status().isNoContent());

        verify(productService).deleteProduct(1L);
    }


    // 9. PUT /api/products/{id} for a missing product -> 404 NOT FOUND
    @Test
    void updateProduct_shouldReturn404WhenProductDoesNotExist()
            throws Exception {

        when(productService.updateProduct(
                eq(999L),
                any(ProductRequestDTO.class)
        )).thenThrow(new ProductNotFoundException(999L));

        mockMvc.perform(put("/api/products/{id}", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isNotFound());

        verify(productService).updateProduct(
                eq(999L),
                any(ProductRequestDTO.class)
        );
    }
}