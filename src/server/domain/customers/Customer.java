package server.domain.customers;

public record Customer(
        String id,
        String fullName,
        String phone,
        CustomerType type
) {}
