-- Field teams, with an identity of their own.
--
-- The demo keeps the team on an order as free text, so every aggregation compares strings and a
-- renamed team silently splits into two. A row with an id fixes that once: the order points at
-- the id, the name is display only, and renaming a team changes nothing else.
--
-- The slug is what a URL and a seed script use, and is what survives an import from the demo's
-- fixed list. V4 and V5 belong to an unmerged dashboard branch, so this numbers past them.
CREATE TABLE IF NOT EXISTS teams (
    team_id UUID PRIMARY KEY,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    short_name VARCHAR(64) NOT NULL,
    region VARCHAR(128),
    -- The dashboard colours a team consistently across the map, the bar chart and the avatar.
    -- Storing it beside the team is what keeps those three agreeing.
    colour VARCHAR(16) NOT NULL,
    initials VARCHAR(4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS teams_slug_key ON teams (slug);

-- Three values and no others. The dashboard keys its badge off this string, so a fourth spelling
-- renders as an unstyled label rather than failing anywhere a test would catch.
ALTER TABLE teams ADD CONSTRAINT teams_status_vocabulary
    CHECK (status IN ('em_campo', 'disponivel', 'fora_servico'));

-- Four placeholder teams, so an order has something to be assigned to on the first day.
--
-- These names come from the demo's fixture list and are NOT the concession's roster. They are
-- configuration, not evidence: unlike a height, nothing here claims to have been measured, and
-- replacing them is an UPDATE rather than a reprocessing run. The ids are fixed so a later
-- import can recognise and replace them instead of adding four more.
INSERT INTO teams (team_id, slug, name, short_name, region, colour, initials, status, created_at, updated_at)
VALUES
    ('11111111-0000-4000-8000-000000000001', 'equipe-1', 'Equipe 1 — Zona Norte', 'Equipe 1',
     'KM 0 – 10', '#5e22f3', 'E1', 'em_campo', NOW(), NOW()),
    ('11111111-0000-4000-8000-000000000002', 'equipe-2', 'Equipe 2 — Zona Sul', 'Equipe 2',
     'KM 10 – 20', '#0ea5a0', 'E2', 'disponivel', NOW(), NOW()),
    ('11111111-0000-4000-8000-000000000003', 'equipe-3', 'Equipe 3 — Manutenção Especial', 'Equipe 3',
     'Pontos críticos', '#dc6d1a', 'E3', 'em_campo', NOW(), NOW()),
    ('11111111-0000-4000-8000-000000000004', 'terceirizada', 'Terceirizada', 'Terceirizada',
     'Sob demanda', '#6b6b80', 'TC', 'disponivel', NOW(), NOW())
-- No conflict target: H2 in PostgreSQL mode, which the contract test runs against, accepts the
-- bare form and rejects `ON CONFLICT (team_id)`. A migration runs once anyway; this only keeps a
-- re-run on an already-seeded database from failing.
ON CONFLICT DO NOTHING;
