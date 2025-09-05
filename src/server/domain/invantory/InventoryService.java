package server.domain.invantory;
import server.util.FileDatabase;
import server.util.Loggers;
import server.shared.Branch;


import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class InventoryService {

    public static record StockInfo(
            String sku, String category, Branch branch,
            int quantity, BigDecimal price) {}

    private final FileDatabase productsDb = new FileDatabase(Path.of("data/products.txt"));

    public synchronized List<Product> listByBranch(Branch branch) {
        return productsDb.readAllLines().stream()
                .filter(s -> !s.isBlank() && !s.startsWith("#"))
                .map(this::parseProduct)
                .filter(p -> p.branch() == branch)
                .collect(Collectors.toList());
    }

    public synchronized Optional<Product> findProduct(Branch branch, String sku) {
        return listByBranch(branch).stream().filter(p -> p.sku().equals(sku)).findFirst();
    }

    public synchronized Optional<StockInfo> getStockInfo(Branch branch, String sku) {
        return findProduct(branch, sku)
                .map(p -> new StockInfo(p.sku(), p.category(), p.branch(), p.quantity(), p.price()));
    }



    public synchronized void updateQuantity(Branch branch, String sku, int delta) {
        List<String> lines = productsDb.readAllLines();
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.isBlank() || s.startsWith("#")) continue;
            Product p = parseProduct(s);
            if (p.branch() == branch && p.sku().equals(sku)) {
                int newQuantity = Math.max(0, p.quantity() + delta);
                Product np = new Product(p.sku(), p.category(), p.branch(), newQuantity, p.price());
                lines.set(i, formatProduct(np));
                productsDb.writeAllLines(lines);
                updated = true;
                
                // Log the transaction
                if (delta > 0) {
                    Loggers.transactions().info(String.format("STOCK_ORDERED: Branch=%s, ID=%s, Category=%s, Quantity=%d, Price=%s", 
                        branch.name(), sku, p.category(), delta, p.price()));
                } else if (delta < 0) {
                    Loggers.transactions().info(String.format("STOCK_SOLD: Branch=%s, ID=%s, Category=%s, Quantity=%d, Price=%s", 
                        branch.name(), sku, p.category(), Math.abs(delta), p.price()));
                }
                break;
            }
        }
        if (!updated) throw new IllegalStateException("SKU not found for update: " + sku + " at " + branch);
    }

    public synchronized boolean removeProduct(Branch branch, String sku) {
        java.util.List<String> lines = productsDb.readAllLines();
        boolean removed = false;
        java.util.List<String> out = new java.util.ArrayList<String>(lines.size());
        Product removedProduct = null;
        for (String s : lines) {
            if (s.isBlank() || s.startsWith("#")) { out.add(s); continue; }
            Product p = parseProduct(s);
            if (p.branch() == branch && p.sku().equals(sku)) {
                removed = true; // skip adding
                removedProduct = p; // store for logging
            } else {
                out.add(s);
            }
        }
        if (removed) {
            productsDb.writeAllLines(out);
            
            // Log the transaction
            if (removedProduct != null) {
                Loggers.transactions().info(String.format("PRODUCT_REMOVED: Branch=%s, ID=%s, Category=%s, Quantity=%d, Price=%s", 
                    branch.name(), sku, removedProduct.category(), removedProduct.quantity(), removedProduct.price()));
            }
        }
        return removed;
    }

    private Product parseProduct(String s) {
        String[] t = s.split(",", -1); // sku,category,branch,quantity,price
        Product p = new Product(
                t[0], t[1], Branch.valueOf(t[2]),
                Integer.parseInt(t[3]),
                new BigDecimal(t[4])
        );
        return p;
    }

    private String formatProduct(Product p) {
        return String.join(",",
                p.sku(), p.category(), p.branch().name(),
                String.valueOf(p.quantity()), p.price().toPlainString()
        );
    }

    public synchronized String addNewProduct(Branch branch, String category, int quantity, BigDecimal price) {
        if (quantity < 0) throw new IllegalArgumentException("quantity must be non-negative");
        if (price.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("price must be non-negative");

        java.util.List<String> lines = productsDb.readAllLines();
        // Allocate a unique numeric SKU
        long maxNumeric = 1000;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1);
            String skuStr = t[0];
            try {
                long val = Long.parseLong(skuStr);
                if (val > maxNumeric) maxNumeric = val;
            } catch (NumberFormatException ignored) { }
        }
        String newSku = String.valueOf(maxNumeric + 1);

        Product np = new Product(newSku, category, branch, quantity, price);
        lines.add(formatProduct(np));
        productsDb.writeAllLines(lines);
        
        // Log the transaction
        Loggers.transactions().info(String.format("PRODUCT_ADDED: Branch=%s, ID=%s, Category=%s, Quantity=%d, Price=%s", 
            branch.name(), newSku, category, quantity, price));
        
        return newSku;
    }
    // מחזיר מוצר כלשהו בקטגוריה (מועדף: במחיר הנמוך ביותר ובכמות > 0)
    public synchronized Optional<Product> findAnyByCategory(Branch branch, String category) {
        return listByBranch(branch).stream()
                .filter(p -> p.category().equalsIgnoreCase(category) && p.quantity() > 0)
                .sorted(Comparator.comparing(Product::price)) // הזול קודם
                .findFirst();
    }

    /** מוריד יחידה אחת ממוצר כלשהו בקטגוריה הנתונה ומעדכן מלאי. מחזיר true אם הצליח */
    public synchronized boolean consumeOneByCategory(Branch branch, String category) {
        Optional<Product> opt = findAnyByCategory(branch, category);
        if (opt.isEmpty()) return false;
        Product p = opt.get();
        updateQuantity(branch, p.sku(), -1);
        return true;
    }

}
