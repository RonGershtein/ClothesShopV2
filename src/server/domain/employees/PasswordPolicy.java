package server.domain.employees;

import server.util.FileDatabase;

import java.nio.file.Path;
import java.util.List;

/**
 * PasswordPolicy with persistence:
 * File: data/password_policy.txt
 * Format:
 *   minimumLength=6
 *   requireDigit=true
 *   requireLetter=true
 */
public class PasswordPolicy {
    private static int minimumLength = 6;
    private static boolean requireDigit = true;
    private static boolean requireLetter = true;

    private static final FileDatabase db = new FileDatabase(Path.of("data/password_policy.txt"));

    static { load(); }

    public static synchronized void configure(int minLen, boolean digit, boolean letter) {
        minimumLength = Math.max(1, minLen);
        requireDigit = digit;
        requireLetter = letter;
        save();
    }


    public static int minimumLength() { return minimumLength; }
    public static boolean requireDigit() { return requireDigit; }
    public static boolean requireLetter() { return requireLetter; }

    private static void load() {
        try {
            List<String> lines = db.readAllLines();
            for (String s : lines) {
                if (s == null) continue;
                String line = s.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] kv = line.split("=", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim(), v = kv[1].trim();
                switch (k) {
                    case "minimumLength": minimumLength = Integer.parseInt(v); break;
                    case "requireDigit":  requireDigit = Boolean.parseBoolean(v); break;
                    case "requireLetter": requireLetter = Boolean.parseBoolean(v); break;
                }
            }
        } catch (Exception ignored) {
            // defaults stay
        }
    }

    private static void save() {
        db.writeAllLines(List.of(
                "minimumLength=" + minimumLength,
                "requireDigit=" + requireDigit,
                "requireLetter=" + requireLetter
        ));
    }
}
