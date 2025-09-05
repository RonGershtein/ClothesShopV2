package server.domain.employees;

import server.shared.Branch;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * EmployeeDirectory (ללא תאימות לאחור, פשוט ונקי).
 * שומר עובדים ב- data/employees.txt בפורמט CSV של 9 עמודות:
 * employeeId,username,passwordHash,role,branch,accountNumber,phone,fullName,nationalId
 *
 * אם הקובץ לא קיים - ייווצר ריק.
 */
public final class EmployeeDirectory {

    private static final Path DATA_DIR = Paths.get("data");
    private static final Path EMP_FILE = DATA_DIR.resolve("employees.txt");

    private final Map<String, Employee> byId = new ConcurrentHashMap<>();
    private final Map<String, Employee> byUsername = new ConcurrentHashMap<>();

    public EmployeeDirectory() {
        try {
            if (!Files.exists(DATA_DIR)) Files.createDirectories(DATA_DIR);
            if (!Files.exists(EMP_FILE)) Files.createFile(EMP_FILE);
            load();
        } catch (IOException e) {
            throw new IllegalStateException("init EmployeeDirectory failed", e);
        }
    }

    // שים לב: כאן מצופה לקבל passwordHash מוכן (sha256)
    public synchronized Employee addEmployee(String username, String passwordHash, String role, Branch branch,
                                             String accountNumber, String phone, String fullName, String nationalId) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(branch, "branch");

        if (byUsername.containsKey(username)) {
            throw new IllegalArgumentException("USERNAME_ALREADY_EXISTS");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("FULL_NAME_REQUIRED");
        }
        if (nationalId == null || nationalId.isBlank()) {
            throw new IllegalArgumentException("NATIONAL_ID_REQUIRED");
        }
        // איסור פסיקים בשדות כדי לשמור CSV פשוט
        if (containsComma(username, accountNumber, phone, fullName, nationalId)) {
            throw new IllegalArgumentException("NO_COMMAS_ALLOWED");
        }

        String id = nextEmployeeId();
        Employee emp = new Employee(id, username, passwordHash, role, branch, accountNumber, phone, fullName, nationalId);
        byId.put(id, emp);
        byUsername.put(username, emp);
        save();
        return emp;
    }

    public synchronized boolean deleteById(String employeeId) {
        Employee e = byId.remove(employeeId);
        if (e != null) {
            byUsername.remove(e.username());
            saveSilently();
            return true;
        }
        return false;
    }

    public Optional<Employee> findById(String employeeId) {
        return Optional.ofNullable(byId.get(employeeId));
    }

    public Optional<Employee> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    public List<Employee> listAll() {
        return byId.values().stream()
                .sorted(Comparator.comparing(Employee::employeeId))
                .toList();
    }


    // ---------- I/O ----------

    private void load() throws IOException {
        byId.clear(); byUsername.clear();
        try (BufferedReader br = Files.newBufferedReader(EMP_FILE, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Employee e = Employee.fromCsv(line);
                byId.put(e.employeeId(), e);
                byUsername.put(e.username(), e);
            }
        }
    }

    private void save() {
        try (BufferedWriter bw = Files.newBufferedWriter(EMP_FILE, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (Employee e : listAll()) {
                bw.write(e.toCsv());
                bw.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("save employees failed", e);
        }
    }

    private void saveSilently() { try { save(); } catch (Exception ignored) {} }

    // ---------- Helpers ----------

    private String nextEmployeeId() {
        int max = 0;
        for (String id : byId.keySet()) {
            if (id != null && id.startsWith("E")) {
                try { max = Math.max(max, Integer.parseInt(id.substring(1))); } catch (NumberFormatException ignored) {}
            }
        }
        return String.format("E%05d", max + 1);
    }

    private static boolean containsComma(String... s) {
        for (String x : s) { if (x != null && x.contains(",")) return true; }
        return false;
    }
}
