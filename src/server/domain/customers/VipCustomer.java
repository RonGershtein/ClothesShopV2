package server.domain.customers;

import java.math.BigDecimal;

public final class VipCustomer implements CustomerType {

    @Override
    public String code() { return "VIP"; }

    /** 12% הנחה */
    @Override
    public BigDecimal applyDiscount(BigDecimal basePrice) {
        if (basePrice == null) return BigDecimal.ZERO;
        return basePrice.multiply(new BigDecimal("0.12"));
    }

    /** חולצה מתנה אם הסכום הסופי (אחרי הנחה) לפחות 300 */
    @Override
    public boolean qualifiesGiftShirt(BigDecimal finalTotal) {
        if (finalTotal == null) return false;
        return finalTotal.compareTo(new BigDecimal("300")) >= 0;
    }
}
