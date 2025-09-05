package server.domain.invantory;

import server.shared.Branch;

import java.math.BigDecimal;

public record Product(
        String sku,
        String category,
        Branch branch,
        int quantity,
        BigDecimal price
) {}
