-- SPDX-License-Identifier: MIT

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    item TEXT NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
