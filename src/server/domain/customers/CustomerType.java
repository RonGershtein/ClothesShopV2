package server.domain.customers;

import java.math.BigDecimal;

public interface CustomerType {
    String code();

    /** מחזיר את ערך ההנחה הכספי על מחיר בסיסי */
    java.math.BigDecimal applyDiscount(BigDecimal basePrice);

    /** האם מגיעה חולצה מתנה, לפי הסכום הסופי אחרי הנחה (דיפולט: לא) */
    default boolean qualifiesGiftShirt(BigDecimal finalTotal) {
        return false;
    }
}
