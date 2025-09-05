package server.domain.customers;

import server.util.FileDatabase;
import server.util.Loggers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Customers are stored in: data/customers.txt
 *   line: id,fullName,phone,type   (type: NEW | RETURNING | VIP)
 *
 * Purchase counts are stored in: data/customer_stats.txt
 *   line: id,count
 *
 * Auto-promotion thresholds:
 *   >= 10 purchases => VIP
 *   >=  2 purchases => RETURNING
 *   else            => NEW
 */
public class CustomerService {

    private final FileDatabase customersDb = new FileDatabase(Path.of("data/customers.txt"));
    private final FileDatabase statsDb     = new FileDatabase(Path.of("data/customer_stats.txt"));

    /** Find by ID in customers file. */
    public Optional<Customer> findById(String id) {
        for (String s : customersDb.readAllLines()) {
            if (s == null) continue;
            String line = s.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] t = line.split(",", -1); // id,fullName,phone,type
            if (t.length < 4) continue;
            if (t[0].equals(id)) {
                return Optional.of(new Customer(t[0], t[1], t[2], typeFrom(t[3])));
            }
        }
        return Optional.empty();
    }

    /** List all customers. */
    public List<Customer> listAll() {
        List<Customer> out = new ArrayList<>();
        for (String s : customersDb.readAllLines()) {
            if (s == null) continue;
            String line = s.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1);
            if (t.length < 4) continue;
            out.add(new Customer(t[0], t[1], t[2], typeFrom(t[3])));
        }
        return out;
    }

    /** Insert or update by id. */
    public void upsert(Customer customer) {
        List<String> lines = new ArrayList<>(customersDb.readAllLines());
        boolean found = false;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] t = line.split(",", -1);
            if (t.length < 4) continue;

            if (t[0].equals(customer.id())) {
                lines.set(i, format(customer));
                found = true;
                break;
            }
        }
        if (!found) lines.add(format(customer));
        customersDb.writeAllLines(lines);
    }

    /** Add a new customer (fails if id already exists). */
    public Customer addCustomer(String id, String fullName, String phone, String typeCode) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Customer ID is required");
        if (findById(id).isPresent()) throw new IllegalArgumentException("Customer already exists: " + id);

        CustomerType type = typeFrom(typeCode);
        Customer c = new Customer(id, fullName == null ? "" : fullName, phone == null ? "" : phone, type);
        upsert(c);
        // initialize stats at zero
        ensureStatsRow(id);
        
        // Log the customer addition
        Loggers.customers().info(String.format("CUSTOMER_ADDED: ID=%s, FullName=%s, Phone=%s, Type=%s", 
            id, fullName, phone, typeCode));
        
        return c;
    }

    /** Record a purchase and auto-promote type if thresholds reached. */
    public void recordPurchase(String id) {
        int count = incrementAndGetCount(id);
        String newTypeCode = tierForCount(count);

        // Update type in customers file if changed
        Customer current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Customer not found: " + id));
        String currentCode = current.type().code();

        if (!currentCode.equals(newTypeCode)) {
            upsert(new Customer(current.id(), current.fullName(), current.phone(), typeFrom(newTypeCode)));
        }
    }

    // ---------- Helpers ----------

    private CustomerType typeFrom(String code) {
        String c = code == null ? "NEW" : code.trim().toUpperCase();
        return switch (c) {
            case "VIP" -> new VipCustomer();
            case "RETURNING" -> new ReturningCustomer();
            default -> new NewCustomer();
        };
    }

    private String tierForCount(int count) {
        if (count >= 10) return "VIP";
        if (count >= 2)  return "RETURNING";
        return "NEW";
    }

    private void ensureStatsRow(String id) {
        List<String> lines = new ArrayList<>(statsDb.readAllLines());
        for (String s : lines) {
            if (s == null) continue;
            String line = s.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1);
            if (t.length < 2) continue;
            if (t[0].equals(id)) return; // already exists
        }
        lines.add(id + ",0");
        statsDb.writeAllLines(lines);
    }

    private int incrementAndGetCount(String id) {
        List<String> lines = new ArrayList<>(statsDb.readAllLines());
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null) continue;
            String line = s.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1);
            if (t.length < 2) continue;

            if (t[0].equals(id)) {
                int cur = parseIntSafe(t[1]);
                int next = cur + 1;
                lines.set(i, id + "," + next);
                statsDb.writeAllLines(lines);
                updated = true;
                return next;
            }
        }
        // no row -> create with 1
        lines.add(id + ",1");
        statsDb.writeAllLines(lines);
        return 1;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private String format(Customer c) {
        return String.join(",",
                c.id(),
                c.fullName(),
                c.phone(),
                c.type().code()
        );
    }
}
