package client.app;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class ChatMenu {
    private final Scanner in;

    public ChatMenu(Scanner in) { this.in = in; }

    public void loop() {
        try (Socket s = new Socket("127.0.0.1", 6060);
             BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter pw = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {
            System.out.println("Connected to chat. Type messages; /quit to exit.");
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = br.readLine()) != null) System.out.println("Peer: " + line);
                } catch (IOException ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            while (true) {
                String message = in.nextLine();
                if ("/quit".equalsIgnoreCase(message)) break;
                pw.println(message);
            }
        } catch (Exception e) {
            System.out.println("Chat error: " + e.getMessage());
        }
    }
}