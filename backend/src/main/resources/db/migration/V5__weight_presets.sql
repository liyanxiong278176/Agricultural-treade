CREATE TABLE gross_weight (id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL UNIQUE, weight_kg DECIMAL(18,6) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, revision INT NOT NULL DEFAULT 1);
ALTER TABLE basket_type ADD COLUMN revision INT NOT NULL DEFAULT 1;
CREATE TABLE weight_preset_revision (kind VARCHAR(10) NOT NULL, preset_id VARCHAR(36) NOT NULL, revision INT NOT NULL, weight_kg DECIMAL(18,6) NOT NULL, PRIMARY KEY(kind,preset_id,revision));
INSERT INTO weight_preset_revision(kind,preset_id,revision,weight_kg) SELECT 'BASKET',id,1,weight_kg FROM basket_type;
