package client.app;

import java.util.Scanner;

public class MainMenu {
    private final Scanner in;

    public MainMenu(Scanner in) { this.in = in; }

    public void loop() {
        while (true) {
            System.out.println("\n=== Shop Network (Console) ===");
            System.out.println("1) Start Client Console");
            System.out.println("2) Chat Only");
            System.out.println("0) Exit");
            System.out.print("Choice: ");
            switch (in.nextLine().trim()) {
                case "1" -> {
                    System.out.println("Starting Client Console...");
                    new ClientConsole().run();
                    return; // Exit after client console finishes
                }
                case "2" -> new ChatMenu(in).loop();
                case "0" -> { return; }
                default -> System.out.println("Invalid choice");
            }
        }
    }
}
