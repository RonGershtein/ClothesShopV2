package server.net;

import server.domain.employees.AuthService;
import server.domain.invantory.InventoryService;
import server.domain.customers.CustomerService;
import server.domain.sales.SalesService;

import server.shared.Branch;
import server.domain.invantory.Product;
import server.domain.customers.Customer;

import server.util.Loggers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.*;

/**
 * Handles a single TCP client for the StoreServer (port 5050).
 * Text protocol (per line):
 *   LOGIN <username> <password> <role: employee|admin>
 *   LOGOUT
 *   LIST <branch>
 *   BUY <branch> <sku> <quantity>
 *   SELL <branch> <sku> <quantity> <customerId>
 *   SELL_MULTI <branch> <customerId> <sku:qty,sku:qty,...>
 *   CUSTOMER_ADD <id> <fullName_underscored> <phone> [type]
 *   CUSTOMER_LIST
 *   ADD_PRODUCT <branch> <category_underscored> <quantity> <price>
 *   REMOVE_PRODUCT <branch> <sku>
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final AuthService auth;
    private final InventoryService inventory;
    private final CustomerService customers;
    private final SalesService sales;

    private String loggedUsername = null;

    public ClientHandler(Socket socket, AuthService auth, InventoryService inventory,
                         CustomerService customers, SalesService sales) {
        this.socket = socket;
        this.auth = auth;
        this.inventory = inventory;
        this.customers = customers;
        this.sales = sales;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            out.println("OK WELCOME");
            String line;

            while ((line = in.readLine()) != null) {
                String[] t = line.trim().split(" ");
                if (t.length == 0) continue;
                String cmd = t[0].toUpperCase();

                if ("LOGIN".equals(cmd)) { // LOGIN <username> <password> <role: employee|admin>
                    if (t.length < 4) { out.println("ERR BAD_ARGS"); continue; }
                    AuthService.LoginResult result = "admin".equalsIgnoreCase(t[3])
                            ? auth.loginAdmin(t[1], t[2])
                            : auth.loginEmployee(t[1], t[2]);

                    if (result == AuthService.LoginResult.SUCCESS) {
                        loggedUsername = t[1];
                        out.println("OK LOGIN");
                    } else if (result == AuthService.LoginResult.ALREADY_CONNECTED) {
                        out.println("ERR LOGIN ALREADY_CONNECTED");
                    } else {
                        out.println("ERR LOGIN INVALID_CREDENTIALS");
                    }
                }
                else if ("LOGOUT".equals(cmd)) {
                    if (loggedUsername != null) auth.logout(loggedUsername);
                    out.println("OK BYE");
                    return;
                }
                else if ("LIST".equals(cmd)) { // LIST <branch>
                    if (t.length < 2) { out.println("ERR BAD_ARGS"); continue; }
                    Branch branch = Branch.valueOf(t[1].toUpperCase());
                    for (Product p : inventory.listByBranch(branch)) {
                        out.println("ITEM " + p.sku() + "," + p.category() + ","
                                + p.branch() + "," + p.quantity() + "," + p.price());
                    }
                    out.println("OK END");
                }
                else if ("BUY".equals(cmd)) { // BUY <branch> <sku> <quantity>
                    if (t.length < 4) { out.println("ERR BAD_ARGS"); continue; }
                    Branch branch = Branch.valueOf(t[1].toUpperCase());
                    String sku = t[2];
                    int quantity = Integer.parseInt(t[3]);
                    inventory.updateQuantity(branch, sku, +quantity);
                    out.println("OK BUY");
                }
                else if ("SELL".equals(cmd)) { // SELL <branch> <sku> <quantity> <customerId>
                    if (t.length < 5) { out.println("ERR BAD_ARGS"); continue; }
                    Branch branch = Branch.valueOf(t[1].toUpperCase());
                    String sku = t[2];
                    int quantity = Integer.parseInt(t[3]);
                    String customerId = t[4];

                    Product product = inventory.findProduct(branch, sku)
                            .orElseThrow(() -> new IllegalStateException("SKU not found in branch"));
                    Customer customer = customers.findById(customerId)
                            .orElseThrow(() -> new IllegalStateException("Customer not found"));

                    if (product.quantity() < quantity) {
                        out.println("ERR NOT_ENOUGH_STOCK");
                        continue;
                    }

                    // Discount based on current type (before promotion)
                    SalesService.SaleSummary summary = sales.sell(product, quantity, customer);
                    inventory.updateQuantity(branch, sku, -quantity);

                    // Record purchase and auto-promote for next time
                    customers.recordPurchase(customerId);

                    // Gift logic + stock consume
                    if (customer.type().qualifiesGiftShirt(summary.finalPrice())) {
                        boolean consumed = inventory.consumeOneByCategory(branch, "SHIRT");
                        if (consumed) {
                            out.println("GIFT_SHIRT 1");
                        } else {
                            out.println("GIFT_SHIRT_OUT_OF_STOCK");
                        }
                    }
                    out.println("Sale Completed " +
                            summary.basePrice() + " " +
                            summary.discountValue() + " " +
                            summary.finalPrice() + " " +
                            summary.customerTypeCode());
                }
                else if ("SELL_MULTI".equals(cmd)) { // SELL_MULTI <branch> <customerId> <sku:qty,sku:qty,...>
                    if (t.length < 4) { out.println("ERR BAD_ARGS"); continue; }

                    // --- Parse args safely ---
                    Branch branch;
                    try {
                        branch = Branch.valueOf(t[1].toUpperCase());
                    } catch (IllegalArgumentException iae) {
                        out.println("ERR BAD_BRANCH");
                        continue;
                    }
                    String customerId = t[2];
                    String spec = t[3];

                    Optional<Customer> oc = customers.findById(customerId);
                    if (oc.isEmpty()) { out.println("ERR CUSTOMER_NOT_FOUND"); continue; }
                    Customer customer = oc.get();

                    // --- Build cart safely ---
                    class Line {
                        String sku; int qty; Product product;
                        BigDecimal unitPrice() { return product.price(); }
                        String category() { return product.category(); }
                    }
                    List<Line> cart = new ArrayList<>();

                    if (spec == null || spec.isBlank()) {
                        out.println("ERR EMPTY_CART");
                        continue;
                    }

                    for (String token : spec.split(",")) {
                        if (token.isBlank()) continue;
                        String[] kv = token.split(":", 2);
                        if (kv.length < 2) { out.println("ERR BAD_ITEM_SPEC"); cart.clear(); break; }
                        String sku = kv[0].trim();
                        int qty;
                        try {
                            qty = Integer.parseInt(kv[1].trim());
                        } catch (NumberFormatException nfe) {
                            out.println("ERR BAD_QTY"); cart.clear(); break;
                        }
                        if (qty <= 0) { out.println("ERR BAD_QTY"); cart.clear(); break; }

                        Optional<Product> op = inventory.findProduct(branch, sku);
                        if (op.isEmpty()) { out.println("ERR SKU_NOT_FOUND " + sku); cart.clear(); break; }

                        Line ln = new Line();
                        ln.sku = sku; ln.qty = qty; ln.product = op.get();
                        cart.add(ln);
                    }
                    if (cart.isEmpty()) continue;

                    // --- Stock check (all-or-nothing) ---
                    for (Line ln : cart) {
                        if (ln.product.quantity() < ln.qty) {
                            out.println("ERR NOT_ENOUGH_STOCK " + ln.sku);
                            cart.clear();
                            break;
                        }
                    }
                    if (cart.isEmpty()) continue;

                    // --- Totals & discount ---
                    BigDecimal baseTotal = BigDecimal.ZERO;
                    for (Line ln : cart) {
                        baseTotal = baseTotal.add(ln.unitPrice().multiply(BigDecimal.valueOf(ln.qty)));
                    }

                    BigDecimal discountAbs = customer.type().applyDiscount(baseTotal);
                    if (discountAbs.compareTo(baseTotal) > 0) discountAbs = baseTotal;
                    BigDecimal finalTotal = baseTotal.subtract(discountAbs);

                    // --- Gift eligibility: רק VIP + מעל 300 (כפי שממומש ב-type) ---
                    boolean giftEligible = customer.type().qualifiesGiftShirt(finalTotal);

                    // --- Commit stock ---
                    for (Line ln : cart) {
                        inventory.updateQuantity(branch, ln.sku, -ln.qty);
                    }

                    // --- Tiering / promotion for next time ---
                    customers.recordPurchase(customerId);

                    // --- Try gift consumption only if eligible ---
                    boolean gifted = false;
                    if (giftEligible) {
                        // ודא שקיימת הפונקציה הזו ב-InventoryService; אם לא – אשלח לך מייד את המימוש
                        gifted = inventory.consumeOneByCategory(branch, "SHIRT");
                    }

                    // --- Log & response header ---
                    Loggers.transactions().info(String.format(
                            "SALE_MULTI: Branch=%s, Customer=%s, Type=%s, Items=%d, Base=%s, Discount=%s, Final=%s, GiftEligible=%s, Gifted=%s",
                            branch.name(), customerId, customer.type().code(), cart.size(),
                            baseTotal.toPlainString(), discountAbs.toPlainString(), finalTotal.toPlainString(),
                            giftEligible, gifted
                    ));

                    StringBuilder hdr = new StringBuilder();
                    hdr.append("OK SALE_MULTI ").append(customer.type().code()).append(" ")
                            .append(baseTotal.toPlainString()).append(" ")
                            .append(discountAbs.toPlainString()).append(" ")
                            .append(finalTotal.toPlainString());
                    if (gifted) hdr.append(" GIFT");
                    out.println(hdr.toString());

                    // --- Per-line breakdown (proportional) ---
                    BigDecimal rate = (baseTotal.signum() == 0)
                            ? BigDecimal.ZERO
                            : discountAbs.divide(baseTotal, 8, java.math.RoundingMode.HALF_UP);

                    for (Line ln : cart) {
                        BigDecimal lineBase  = ln.unitPrice().multiply(BigDecimal.valueOf(ln.qty));
                        BigDecimal lineDisc  = lineBase.multiply(rate);
                        BigDecimal lineFinal = lineBase.subtract(lineDisc);
                        out.println(String.join(" ",
                                "LINE", ln.sku, ln.category().replace(' ', '_'),
                                String.valueOf(ln.qty),
                                ln.unitPrice().toPlainString(),
                                lineBase.toPlainString(),
                                lineDisc.toPlainString(),
                                lineFinal.toPlainString()
                        ));
                    }

                    // --- Gift status line (ברור ושקוף לקליינט) ---
                    if (giftEligible) {
                        if (gifted) out.println("GIFT_SHIRT 1");
                        else        out.println("GIFT_SHIRT_OUT_OF_STOCK");
                    }

                    out.println("OK END");
                }
                else if ("CUSTOMER_ADD".equals(cmd)) { // CUSTOMER_ADD <id> <fullName_underscored> <phone> [type]
                    if (t.length < 4) { out.println("ERR BAD_ARGS"); continue; }
                    String id = t[1];
                    String fullName = t[2].replace('_', ' ');
                    String phone = t[3];
                    String type = (t.length >= 5) ? t[4].toUpperCase() : "NEW";
                    try {
                        customers.addCustomer(id, fullName, phone, type);
                        out.println("OK CUSTOMER_ADDED");
                    } catch (Exception ex) {
                        out.println("ERR " + ex.getMessage().replace(' ', '_'));
                    }
                }
                else if ("CUSTOMER_LIST".equals(cmd)) { // returns CUST lines
                    for (Customer c : customers.listAll()) {
                        out.println("CUST " + c.id() + "," + c.fullName() + "," + c.phone() + "," + c.type().code());
                    }
                    out.println("OK END");
                }
                else if ("ADD_PRODUCT".equals(cmd)) { // ADD_PRODUCT <branch> <category> <quantity> <price>
                    if (t.length < 5) { out.println("ERR BAD_ARGS"); continue; }
                    Branch branch = Branch.valueOf(t[1].toUpperCase());
                    String category = t[2].replace('_', ' ');
                    int quantity = Integer.parseInt(t[3]);
                    BigDecimal price = new BigDecimal(t[4]);
                    try {
                        String newSku = inventory.addNewProduct(branch, category, quantity, price);
                        out.println("OK PRODUCT_ADDED " + newSku + " " + category.replace(' ', '_'));
                    } catch (Exception ex) {
                        out.println("ERR " + ex.getMessage().replace(' ', '_'));
                    }
                }
                else if ("REMOVE_PRODUCT".equals(cmd)) { // REMOVE_PRODUCT <branch> <sku>
                    if (t.length < 3) { out.println("ERR BAD_ARGS"); continue; }
                    Branch branch = Branch.valueOf(t[1].toUpperCase());
                    String sku = t[2];
                    try {
                        boolean removed = inventory.removeProduct(branch, sku);
                        if (removed) out.println("OK REMOVED");
                        else out.println("ERR SKU_NOT_FOUND");
                    } catch (Exception ex) {
                        out.println("ERR " + ex.getMessage().replace(' ', '_'));
                    }
                }
                else {
                    out.println("ERR UNKNOWN_CMD");
                }
            }
        } catch (Exception e) {
            Loggers.system().severe("Client error: " + e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            if (loggedUsername != null) auth.logout(loggedUsername);
        }
    }
}
