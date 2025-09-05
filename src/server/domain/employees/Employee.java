package server.domain.employees;

import server.shared.Branch;

/**
 * Employee domain model (פשוט וקצר, ללא תאימות לאחור).
 * פורמט CSV קבוע: 9 שדות מופרדים בפסיק (ללא פסיקים בתוך שדות).
 *
 * employeeId,username,passwordHash,role,branch,accountNumber,phone,fullName,nationalId
 */
public final class Employee {
    private final String employeeId;
    private final String username;
    private final String passwordHash; // מאוחסן כהאש
    private final String role;         // SALESPERSON/CASHIER/SHIFT_MANAGER
    private final Branch branch;       // HOLON/TEL_AVIV/RISHON
    private final String accountNumber;
    private final String phone;
    private final String fullName;     // חדש
    private final String nationalId;   // חדש

    public Employee(String employeeId, String username, String passwordHash,
                    String role, Branch branch, String accountNumber, String phone,
                    String fullName, String nationalId) {
        this.employeeId = employeeId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.branch = branch;
        this.accountNumber = accountNumber;
        this.phone = phone;
        this.fullName = fullName;
        this.nationalId = nationalId;
    }

    public String employeeId()   { return employeeId; }
    public String username()     { return username; }
    public String passwordHash() { return passwordHash; }
    public String role()         { return role; }
    public Branch branch()       { return branch; }
    public String accountNumber(){ return accountNumber; }
    public String phone()        { return phone; }
    public String fullName()     { return fullName; }
    public String nationalId()   { return nationalId; }

    // CSV פשוט: 9 שדות, ללא פסיקים בשדות
    public String toCsv() {
        return String.join(",",
                employeeId, username, passwordHash, role, branch.name(),
                accountNumber, phone, fullName, nationalId
        );
    }

    public static Employee fromCsv(String line) {
        String[] t = line.split(",", -1);
        if (t.length != 9) throw new IllegalArgumentException("BAD_EMP_CSV");
        return new Employee(
                t[0], t[1], t[2], t[3],
                server.shared.Branch.valueOf(t[4]),
                t[5], t[6], t[7], t[8]
        );
    }
}
