 package com.example.product_service.service;

import com.example.product_service.dto.ProductRequestDTO;
import com.example.product_service.dto.ProductResponseDTO;
import com.example.product_service.entity.Product;
import com.example.product_service.exception.ProductNotFoundException;
import com.example.product_service.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private ProductService productService;

    private Product product;
    private ProductRequestDTO request;

    @BeforeEach
    void setUp() {

        // Inject the mocked EntityManager into ProductService.
        // Mockito does not automatically process @PersistenceContext.
        ReflectionTestUtils.setField(
                productService,
                "entityManager",
                entityManager
        );

        // Prepare a sample product.
        product = new Product();
        product.setName("Wireless Mouse");
        product.setDescription("Wireless mouse with USB receiver");
        product.setPrice(new BigDecimal("999.00"));
        product.setCategory("Electronics");

        // Prepare a sample request DTO.
        request = new ProductRequestDTO();
        request.setName("Wireless Mouse");
        request.setDescription("Wireless mouse with USB receiver");
        request.setPrice(new BigDecimal("999.00"));
        request.setCategory("Electronics");
    }

    @Test
    void createProduct_shouldReturnCreatedProduct() {

        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponseDTO response =
                productService.createProduct(request);

        assertNotNull(response);
        assertEquals("Wireless Mouse", response.getName());
        assertEquals(
                "Wireless mouse with USB receiver",
                response.getDescription()
        );
        assertEquals(
                new BigDecimal("999.00"),
                response.getPrice()
        );
        assertEquals("Electronics", response.getCategory());

        verify(productRepository).save(any(Product.class));
    }

    @Test
    void getAllProducts_shouldReturnAllProducts() {

        when(productRepository.findAll())
                .thenReturn(List.of(product));

        List<ProductResponseDTO> response =
                productService.getAllProducts();

        assertEquals(1, response.size());
        assertEquals("Wireless Mouse", response.get(0).getName());
        assertEquals(
                new BigDecimal("999.00"),
                response.get(0).getPrice()
        );

        verify(productRepository).findAll();
    }

    @Test
    void getProductById_shouldReturnProductWhenItExists() {

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        ProductResponseDTO response =
                productService.getProductById(1L);

        assertNotNull(response);
        assertEquals("Wireless Mouse", response.getName());
        assertEquals(
                new BigDecimal("999.00"),
                response.getPrice()
        );

        verify(productRepository).findById(1L);
    }

    @Test
    void getProductById_shouldThrowExceptionWhenProductDoesNotExist() {

        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                ProductNotFoundException.class,
                () -> productService.getProductById(999L)
        );

        verify(productRepository).findById(999L);
    }

    @Test
    void updateProduct_shouldUpdateExistingProduct() {

        ProductRequestDTO updateRequest = new ProductRequestDTO();
        updateRequest.setName("Gaming Mouse");
        updateRequest.setDescription("Wireless gaming mouse");
        updateRequest.setPrice(new BigDecimal("1499.00"));
        updateRequest.setCategory("Gaming");

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(productRepository.saveAndFlush(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        doNothing()
                .when(entityManager)
                .refresh(any(Product.class));

        ProductResponseDTO response =
                productService.updateProduct(1L, updateRequest);

        assertNotNull(response);
        assertEquals("Gaming Mouse", response.getName());
        assertEquals(
                "Wireless gaming mouse",
                response.getDescription()
        );
        assertEquals(
                new BigDecimal("1499.00"),
                response.getPrice()
        );
        assertEquals("Gaming", response.getCategory());

        verify(productRepository).findById(1L);
        verify(productRepository).saveAndFlush(any(Product.class));
        verify(entityManager).refresh(any(Product.class));
    }

    @Test
    void updateProduct_shouldThrowExceptionWhenProductDoesNotExist() {

        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                ProductNotFoundException.class,
                () -> productService.updateProduct(999L, request)
        );

        verify(productRepository, never())
                .saveAndFlush(any(Product.class));

        verifyNoInteractions(entityManager);
    }

    @Test
    void deleteProduct_shouldDeleteExistingProduct() {

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        productService.deleteProduct(1L);

        verify(productRepository).findById(1L);
        verify(productRepository).delete(product);
    }

    @Test
    void deleteProduct_shouldThrowExceptionWhenProductDoesNotExist() {

        when(productRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                ProductNotFoundException.class,
                () -> productService.deleteProduct(999L)
        );

        verify(productRepository, never())
                .delete(any(Product.class));
    }
}
