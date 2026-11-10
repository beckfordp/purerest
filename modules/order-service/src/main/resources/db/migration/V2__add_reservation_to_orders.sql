-- SPDX-License-Identifier: MIT

ALTER TABLE orders
    ADD COLUMN reservation_id UUID NOT NULL,
    ADD COLUMN reserved_quantity INT NOT NULL,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'reserved';
