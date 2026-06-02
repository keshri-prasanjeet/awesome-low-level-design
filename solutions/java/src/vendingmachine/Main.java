package vendingmachine;

/**
 * Main class to independently run VendingMachineDemo for testing purposes.
 * This serves as an entry point to test the Vending Machine system.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("=".repeat(50));
        System.out.println("VENDING MACHINE SYSTEM - TEST RUN");
        System.out.println("=".repeat(50));

        // Run the VendingMachineDemo
        VendingMachineDemo.main(args);

        System.out.println("\n" + "=".repeat(50));
        System.out.println("TEST COMPLETED SUCCESSFULLY");
        System.out.println("=".repeat(50));
    }
}