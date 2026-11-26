-- Tables of the 'catalog' schema, loaded from the public Olist dataset (specs/LAB-17).
-- Nothing here references another schema: orders point at products and sellers by id, never the other way round.

CREATE TABLE product_categories (
    name    text PRIMARY KEY,
    name_en text
);

CREATE TABLE products (
    product_id         text PRIMARY KEY,
    category_name      text REFERENCES product_categories (name),
    name_length        integer,
    description_length integer,
    photos_count       integer,
    weight_g           integer CHECK (weight_g >= 0),
    length_cm          integer CHECK (length_cm >= 0),
    height_cm          integer CHECK (height_cm >= 0),
    width_cm           integer CHECK (width_cm >= 0),
    reference_price    numeric(10,2) CHECK (reference_price >= 0),
    stock              integer NOT NULL CHECK (stock >= 0)
);

CREATE INDEX products_category_name_idx ON products (category_name);

CREATE TABLE sellers (
    seller_id       text PRIMARY KEY,
    zip_code_prefix text,
    city            text,
    state           text
);
