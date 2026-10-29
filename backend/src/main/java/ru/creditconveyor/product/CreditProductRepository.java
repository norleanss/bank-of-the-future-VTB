package ru.creditconveyor.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface CreditProductRepository extends JpaRepository<CreditProduct, String> {

    List<CreditProduct> findByActiveTrueOrderBySortOrder();

    Optional<CreditProduct> findByCodeAndActiveTrue(String code);
}
