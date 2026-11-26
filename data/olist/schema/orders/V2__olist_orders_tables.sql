-- Tables of the 'orders' schema, loaded from the public Olist dataset (specs/LAB-17).
-- Relationships stay inside this schema. An order item points at its product and its seller by id only: the owner of
-- those rows is another module, so there is deliberately no foreign key and no join across schemas.

CREATE TABLE customers (
    customer_id        text PRIMARY KEY,
    customer_unique_id text NOT NULL,
    zip_code_prefix    text,
    city               text,
    state              text
);

CREATE INDEX customers_customer_unique_id_idx ON customers (customer_unique_id);

CREATE TABLE orders (
    order_id                 text PRIMARY KEY,
    customer_id              text NOT NULL REFERENCES customers (customer_id),
    status                   text NOT NULL,
    purchased_at             timestamp,
    approved_at              timestamp,
    delivered_to_carrier_at  timestamp,
    delivered_to_customer_at timestamp,
    estimated_delivery_at    timestamp
);

CREATE INDEX orders_customer_id_idx ON orders (customer_id);
CREATE INDEX orders_status_idx ON orders (status);

CREATE TABLE order_items (
    order_id          text          NOT NULL REFERENCES orders (order_id),
    item_number       integer       NOT NULL,
    product_id        text          NOT NULL,
    seller_id         text          NOT NULL,
    shipping_limit_at timestamp,
    price             numeric(10,2) NOT NULL CHECK (price >= 0),
    freight_value     numeric(10,2) NOT NULL CHECK (freight_value >= 0),
    PRIMARY KEY (order_id, item_number)
);

CREATE INDEX order_items_product_id_idx ON order_items (product_id);
CREATE INDEX order_items_seller_id_idx ON order_items (seller_id);

CREATE TABLE order_payments (
    order_id         text          NOT NULL REFERENCES orders (order_id),
    payment_sequence integer       NOT NULL,
    payment_type     text          NOT NULL,
    installments     integer       NOT NULL CHECK (installments >= 0),
    payment_value    numeric(10,2) NOT NULL CHECK (payment_value >= 0),
    PRIMARY KEY (order_id, payment_sequence)
);

-- review_id is not unique in the source (the same id appears on different orders), hence the surrogate key.
CREATE TABLE order_reviews (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id       text     NOT NULL,
    order_id        text     NOT NULL REFERENCES orders (order_id),
    score           smallint NOT NULL CHECK (score >= 1 AND score <= 5),
    comment_title   text,
    comment_message text,
    created_at      timestamp,
    answered_at     timestamp
);

CREATE INDEX order_reviews_review_id_idx ON order_reviews (review_id);
CREATE INDEX order_reviews_order_id_idx ON order_reviews (order_id);
