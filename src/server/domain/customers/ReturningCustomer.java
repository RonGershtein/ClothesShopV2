package server.domain.customers;

import java.math.BigDecimal;

public class ReturningCustomer implements CustomerType {
    @Override public String code() { return "RETURNING"; }
    @Override public BigDecimal applyDiscount(BigDecimal basePrice) {
        // 5% discount
        return basePrice.multiply(new BigDecimal("0.05"));
    }
}
