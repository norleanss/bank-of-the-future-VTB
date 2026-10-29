package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ru.creditconveyor.api.PaymentEstimateDto;
import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.api.ProductDto;
import ru.creditconveyor.api.ProductMatchRequestDto;
import ru.creditconveyor.api.ProductMatchResponseDto;
import ru.creditconveyor.api.ProductMatchResponseItemsInnerDto;
import ru.creditconveyor.api.ProductRefDto;
import ru.creditconveyor.api.ProductsApi;
import ru.creditconveyor.identity.ClientCompanies;

@RestController
class ProductController implements ProductsApi {

    private final CreditProductRepository products;
    private final ProductMatcher matcher;
    private final ClientCompanies clientCompanies;
    private final PaymentEstimator paymentEstimator;

    ProductController(CreditProductRepository products, ProductMatcher matcher, ClientCompanies clientCompanies,
                      PaymentEstimator paymentEstimator) {
        this.products = products;
        this.matcher = matcher;
        this.clientCompanies = clientCompanies;
        this.paymentEstimator = paymentEstimator;
    }

    @Override
    public ResponseEntity<List<ProductDto>> listProducts() {
        return ResponseEntity.ok(products.findByActiveTrueOrderBySortOrder().stream()
                .map(ProductController::toDto)
                .toList());
    }

    @Override
    public ResponseEntity<ProductDto> getProduct(ProductCodeDto code) {
        return products.findByCodeAndActiveTrue(code.getValue())
                .map(ProductController::toDto)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Продукт " + code.getValue() + " не найден"));
    }

    @Override
    public ResponseEntity<ProductMatchResponseDto> matchProducts(ProductMatchRequestDto request) {
        int businessAgeMonths = clientCompanies.companyProfile(request.getCompanyId()).businessAgeMonths();
        var matchRequest = new ProductMatcher.Request(
                request.getPurpose(),
                new BigDecimal(request.getRequestedAmount()),
                request.getTerm(),
                request.getTermUnit(),
                request.getFundingMode());
        List<ProductMatchResponseItemsInnerDto> items = matcher
                .match(matchRequest, products.findByActiveTrueOrderBySortOrder(), businessAgeMonths).stream()
                .map(match -> new ProductMatchResponseItemsInnerDto(
                        new ProductRefDto(ProductCodeDto.fromValue(match.product().getCode()), match.product().getName()),
                        match.suitable(),
                        match.requiresSubscription(),
                        match.recommended(),
                        match.reasons())
                        .estimate(match.fits() ? estimate(match, matchRequest) : null))
                .toList();
        return ResponseEntity.ok(new ProductMatchResponseDto(items));
    }

    /** Без подписки — если продукт подходит на базовых условиях; с подпиской — если она у продукта есть. */
    private PaymentEstimateDto estimate(ProductMatcher.Match match, ProductMatcher.Request request) {
        ProductTerms product = ProductTerms.of(match.product());
        PaymentEstimator.Estimate base = match.suitable()
                ? paymentEstimator.estimate(product, request.amount(), request.term(), request.termUnit(), false)
                : null;
        PaymentEstimator.Estimate withSubscription = ProductRules.offersSubscription(match.product())
                ? paymentEstimator.estimate(product, request.amount(), request.term(), request.termUnit(), true)
                : null;
        PaymentEstimator.Estimate any = base != null ? base : withSubscription;
        return new PaymentEstimateDto(any.repaymentType())
                .monthlyPayment(base != null ? money(base.monthlyPayment()) : null)
                .monthlyPaymentWithSubscription(withSubscription != null ? money(withSubscription.monthlyPayment()) : null)
                .subscriptionFee(withSubscription != null && withSubscription.subscriptionFee() != null
                        ? money(withSubscription.subscriptionFee())
                        : null);
    }

    private static ProductDto toDto(CreditProduct product) {
        return new ProductDto(
                        ProductCodeDto.fromValue(product.getCode()),
                        product.getName(),
                        money(product.getMinAmount()),
                        money(product.getMaxAmount()),
                        product.getTermKind(),
                        product.getTermUnit(),
                        product.getMinTerm(),
                        product.getMaxTerm(),
                        product.isAutoRenewal(),
                        product.getRateUnit(),
                        product.getBaseRate(),
                        product.getFundingModes(),
                        product.getRepaymentTypes(),
                        product.getMinBusinessAgeMonths())
                .description(product.getDescription())
                .maxTermWithSubscription(product.getMaxTermWithSubscription())
                .agreementTermMonths(product.getAgreementTermMonths())
                .rateWithSubscription(product.getRateWithSubscription())
                .subscriptionDescription(product.getSubscriptionDescription())
                .subscriptionFeePercent(product.getSubscriptionFeePercent());
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
