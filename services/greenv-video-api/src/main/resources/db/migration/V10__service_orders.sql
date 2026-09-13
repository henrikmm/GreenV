-- Service orders, and the measured stretches each one covers.
--
-- The demo's order points at KMZ polygons, because that is all it has. Here an order points at
-- measured segments, which is the only thing in this system that carries a real height and a
-- real position. One order can cover several, which is how the demo's combined order works and
-- why the targets live in their own table rather than as a column.
--
-- The vocabulary below is the one already on screen — `pendente`, `em_andamento`, `concluida`,
-- `cancelada` — kept in Portuguese on purpose. It is a term an operations team says out loud,
-- and translating it in the database would invent a second name for the same thing, with a
-- mapping layer to keep them in step. This numbers past V9, which creates the teams an order
-- points at.
CREATE TABLE IF NOT EXISTS service_orders (
    order_id UUID PRIMARY KEY,
    -- OS-ROÇ-202609-1098: what a crew reads out on the radio. Unique so two dashboards cannot
    -- mint the same one.
    reference VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    team_id UUID REFERENCES teams (team_id) ON DELETE SET NULL,
    scheduled_for DATE,
    notes TEXT,
    equipment VARCHAR(256),
    -- Copied from the targets when the order is opened, not joined at read time: an order is a
    -- decision taken against the evidence of that moment, and a later measurement must not
    -- silently rewrite what was ordered.
    area_square_metres DOUBLE PRECISION,
    vegetation_level SMALLINT,
    centre_lat DOUBLE PRECISION,
    centre_lon DOUBLE PRECISION,
    created_by VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS service_orders_reference_key ON service_orders (reference);
CREATE INDEX IF NOT EXISTS service_orders_status_created_idx ON service_orders (status, created_at DESC);
CREATE INDEX IF NOT EXISTS service_orders_team_idx ON service_orders (team_id, created_at DESC);

ALTER TABLE service_orders ADD CONSTRAINT service_orders_status_vocabulary
    CHECK (status IN ('pendente', 'em_andamento', 'concluida', 'cancelada'));
ALTER TABLE service_orders ADD CONSTRAINT service_orders_priority_vocabulary
    CHECK (priority IN ('baixa', 'media', 'alta', 'urgente'));
ALTER TABLE service_orders ADD CONSTRAINT service_orders_level_range
    CHECK (vegetation_level IS NULL OR vegetation_level BETWEEN 1 AND 3);

-- Which measured stretches the order covers. No foreign key to capture_segments: that table is
-- keyed by (session_id, segment_index) and a session can be retired while the order it justified
-- stays on the books as a record of work done.
CREATE TABLE IF NOT EXISTS service_order_segments (
    order_id UUID NOT NULL REFERENCES service_orders (order_id) ON DELETE CASCADE,
    session_id UUID NOT NULL,
    segment_index INTEGER NOT NULL,
    PRIMARY KEY (order_id, session_id, segment_index)
);

CREATE INDEX IF NOT EXISTS service_order_segments_segment_idx
    ON service_order_segments (session_id, segment_index);

-- Every status an order has ever had, in order. Without it "concluída" is a fact with no date,
-- and no chart of throughput can be drawn from real data.
CREATE TABLE IF NOT EXISTS service_order_events (
    event_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES service_orders (order_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    note TEXT,
    recorded_by VARCHAR(256),
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS service_order_events_order_idx
    ON service_order_events (order_id, recorded_at);

ALTER TABLE service_order_events ADD CONSTRAINT service_order_events_status_vocabulary
    CHECK (status IN ('pendente', 'em_andamento', 'concluida', 'cancelada'));
