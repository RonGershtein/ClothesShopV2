package server.domain.sales;

import server.domain.customers.Customer;
import server.domain.invantory.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class SalesService {

    public static class SaleSummary {
        private final BigDecimal basePrice;
        private final BigDecimal discountValue;
        private final BigDecimal finalPrice;
        private final String customerTypeCode;

        public SaleSummary(BigDecimal basePrice, BigDecimal discountValue, BigDecimal finalPrice, String customerTypeCode) {
            this.basePrice = basePrice;
            this.discountValue = discountValue;
            this.finalPrice = finalPrice;
            this.customerTypeCode = customerTypeCode;
        }

        public BigDecimal basePrice()     { return basePrice; }
        public BigDecimal discountValue() { return discountValue; }
        public BigDecimal finalPrice()    { return finalPrice; }
        public String customerTypeCode()  { return customerTypeCode; }
    }

    public static class LineRequest {
        public final Product product;
        public final int quantity;
        public LineRequest(Product product, int quantity) {
            if (product == null) throw new IllegalArgumentException("product is null");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
            this.product = product;
            this.quantity = quantity;
        }
    }

    public static class LineSummary {
        public final String sku;
        public final String category;
        public final int quantity;
        public final BigDecimal unitPrice;
        public final BigDecimal base;
        public final BigDecimal discount;
        public final BigDecimal total;

        public LineSummary(String sku, String category, int quantity, BigDecimal unitPrice,
                           BigDecimal base, BigDecimal discount, BigDecimal total) {
            this.sku = sku;
            this.category = category;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.base = base;
            this.discount = discount;
            this.total = total;
        }
    }

    public static class CartSummary {
        public final String customerTypeCode;
        public final BigDecimal baseTotal;
        public final BigDecimal discountTotal;
        public final BigDecimal finalTotal;
        public final boolean giftShirt;
        public final List<LineSummary> lines;

        public CartSummary(String customerTypeCode,
                           BigDecimal baseTotal,
                           BigDecimal discountTotal,
                           BigDecimal finalTotal,
                           boolean giftShirt,
                           List<LineSummary> lines) {
            this.customerTypeCode = customerTypeCode;
            this.baseTotal = baseTotal;
            this.discountTotal = discountTotal;
            this.finalTotal = finalTotal;
            this.giftShirt = giftShirt;
            this.lines = lines;
        }
    }

    public SaleSummary sell(Product product, int quantity, Customer customer) {
        BigDecimal basePrice = product.price().multiply(BigDecimal.valueOf(quantity));
        BigDecimal discountValue = customer.type().applyDiscount(basePrice);
        BigDecimal finalPrice = basePrice.subtract(discountValue);
        return new SaleSummary(scale(basePrice), scale(discountValue), scale(finalPrice), customer.type().code());
    }

    public CartSummary sellMulti(List<LineRequest> items, Customer customer) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("empty cart");

        List<LineSummary> lines = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO, disc = BigDecimal.ZERO;

        for (LineRequest r : items) {
            BigDecimal lineBase = r.product.price().multiply(BigDecimal.valueOf(r.quantity));
            BigDecimal lineDiscount = customer.type().applyDiscount(lineBase);
            BigDecimal lineTotal = lineBase.subtract(lineDiscount);

            base = base.add(lineBase);
            disc = disc.add(lineDiscount);

            lines.add(new LineSummary(
                    r.product.sku(),
                    r.product.category(),
                    r.quantity,
                    scale(r.product.price()),
                    scale(lineBase),
                    scale(lineDiscount),
                    scale(lineTotal)
            ));
        }

        BigDecimal finalTotal = base.subtract(disc);

        // 🔁 השינוי כאן: שואלים את סוג הלקוח אם מגיעה מתנה (במקום לחשב כאן כלל קשיח)
        boolean gift = customer.type().qualifiesGiftShirt(finalTotal);

        return new CartSummary(
                customer.type().code(),
                scale(base),
                scale(disc),
                scale(finalTotal),
                gift,
                lines
        );
    }

    private static BigDecimal scale(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
