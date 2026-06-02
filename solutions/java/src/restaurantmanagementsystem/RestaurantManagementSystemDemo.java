package restaurantmanagementsystem;

import restaurantmanagementsystem.decorator.Bill;
import restaurantmanagementsystem.model.Order;

import java.util.Arrays;

public class RestaurantManagementSystemDemo {
    public static void run() {
        RestaurantManagementSystemFacade restaurant = RestaurantManagementSystemFacade.getInstance();

        restaurant.addTable(1, 4);
        restaurant.addWaiter("W1", "Alice");
        restaurant.addChef("C1", "Bob");

        restaurant.addMenuItem("M1", "Burger", 9.99);
        restaurant.addMenuItem("M2", "Pizza", 12.99);
        restaurant.addMenuItem("M3", "Salad", 7.99);

        Order order = restaurant.takeOrder(1, "W1", Arrays.asList("M1", "M3"));
        restaurant.markItemsAsReady(order.getOrderId());
        restaurant.serveOrder("W1", order.getOrderId());

        Bill bill = restaurant.generateBill(order.getOrderId());
        bill.printBill();
    }
}
