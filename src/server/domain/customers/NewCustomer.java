package server.domain.customers;

import java.math.BigDecimal;

public class NewCustomer implements CustomerType {
    @Override public String code() { return "NEW"; }
    @Override public BigDecimal applyDiscount(BigDecimal basePrice) {
        return BigDecimal.ZERO; // no discount
    }
}
