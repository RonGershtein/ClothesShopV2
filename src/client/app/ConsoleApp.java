package client.app;

import java.util.Scanner;

public class ConsoleApp {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        new MainMenu(in).loop();
        System.out.println("Bye");
    }
}
