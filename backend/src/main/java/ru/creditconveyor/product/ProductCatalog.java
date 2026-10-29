package ru.creditconveyor.product;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Условия действующих продуктов каталога для решения по заявке.
 */
@Service
@Transactional(readOnly = true)
public class ProductCatalog {

    private final CreditProductRepository products;

    ProductCatalog(CreditProductRepository products) {
        this.products = products;
    }

    public Optional<ProductTerms> terms(String productCode) {
        return products.findByCodeAndActiveTrue(productCode).map(ProductTerms::of);
    }

    /** В порядке каталога. */
    public List<ProductTerms> active() {
        return products.findByActiveTrueOrderBySortOrder().stream().map(ProductTerms::of).toList();
    }
}
