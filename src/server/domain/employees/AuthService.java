package server.domain.employees;

import server.util.FileDatabase;
import server.util.Loggers;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class AuthService {
    private final FileDatabase employeesDb = new FileDatabase(Path.of("data/employees.txt"));
    private final Set<String> activeUsers = Collections.synchronizedSet(new HashSet<>());

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // Login result enum to distinguish between different failure reasons
    public enum LoginResult {
        SUCCESS,
        INVALID_CREDENTIALS,
        ALREADY_CONNECTED
    }

    public LoginResult loginAdmin(String username, String password) {
        // Easy admin (as requested)
        if ("admin".equals(username) && "admin".equals(password)) {
            synchronized (activeUsers) {
                if (activeUsers.contains(username)) {
                    return LoginResult.ALREADY_CONNECTED;
                }
                activeUsers.add(username);
            }
            return LoginResult.SUCCESS;
        }

        // Optional: support ADMIN role from file too
        String hash = sha256(password);
        for (String line : employeesDb.readAllLines()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1);
            if (t.length < 7) continue;
            if (t[1].equals(username) && t[2].equals(hash) && "ADMIN".equalsIgnoreCase(t[3])) {
                synchronized (activeUsers) {
                    if (activeUsers.contains(username)) {
                        return LoginResult.ALREADY_CONNECTED;
                    }
                    activeUsers.add(username);
                }
                return LoginResult.SUCCESS;
            }
        }
        return LoginResult.INVALID_CREDENTIALS;
    }

    public LoginResult loginEmployee(String username, String password) {
        String hash = sha256(password);
        for (String line : employeesDb.readAllLines()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] t = line.split(",", -1); // employeeId,username,hash,role,branch,accountNumber,phone
            if (t.length < 7) continue;
            if (t[1].equals(username) && t[2].equals(hash)) {
                synchronized (activeUsers) {
                    if (activeUsers.contains(username)) {
                        Loggers.auth().warning("Double login blocked: " + username);
                        return LoginResult.ALREADY_CONNECTED;
                    }
                    activeUsers.add(username);
                }
                Loggers.auth().info("Employee login OK: " + username);
                return LoginResult.SUCCESS;
            }
        }
        Loggers.auth().warning("Employee login FAIL: " + username);
        return LoginResult.INVALID_CREDENTIALS;
    }

    public void logout(String username) {
        if (username != null) activeUsers.remove(username);
    }
}
