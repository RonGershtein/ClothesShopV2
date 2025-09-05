package server.app;

import server.domain.employees.AuthService;
import server.domain.invantory.InventoryService;
import server.domain.customers.CustomerService;
import server.domain.sales.SalesService;

import server.net.ClientHandler;
import server.util.Loggers;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StoreServer {
    private final int port;
    private final AuthService auth = new AuthService();
    private final InventoryService inventory = new InventoryService();
    private final CustomerService customers = new CustomerService();
    private final SalesService sales = new SalesService();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public StoreServer(int port) {
        this.port = port;
        // סוגר את מאגר־השרשורים כשמבקשים לסגור את התהליך
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { pool.shutdownNow(); } catch (Exception ignored) {}
        }));
    }

    /** Starts the server loop and handles clients. Never throws to caller; logs on error. */
    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Loggers.system().info("StoreServer started on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(new ClientHandler(socket, auth, inventory, customers, sales));
            }
        } catch (IOException e) {
            Loggers.system().severe("StoreServer fatal error: " + e.getMessage());
        } finally {
            try { pool.shutdownNow(); } catch (Exception ignored) {}
        }
    }

    public static void main(String[] args) {
        int port = 5050;
        if (args != null && args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        new StoreServer(port).start();
    }
}
