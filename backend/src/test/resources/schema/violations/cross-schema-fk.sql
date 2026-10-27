-- Fixture S1 (module catalog): a foreign key that crosses into another module's schema.
CREATE TABLE catalog.product_offer (
    id BIGINT PRIMARY KEY,
    order_ref BIGINT REFERENCES orders.order_ref (id)
);
