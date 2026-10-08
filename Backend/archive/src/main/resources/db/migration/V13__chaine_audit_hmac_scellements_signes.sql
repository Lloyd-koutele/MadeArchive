-- Chaîne du journal d'audit : HMAC avec une clé du HSM, scellements signés par la clé système, chaînés entre eux.
--
-- Avant : chaque maillon était un SHA-256 simple — un algorithme public, sans secret : quiconque pouvait écrire
-- dans la base pouvait réécrire toute la chaîne et recalculer chaque empreinte. Désormais les nouvelles entrées
-- sont chaînées avec un HMAC-SHA256 dont la clé reste dans le HSM : sans elle, la chaîne ne se recalcule pas. Et
-- comme chaque maillon dépend de TOUS les précédents, le premier maillon HMAC verrouille aussi l'historique
-- antérieur (chaîné en SHA-256 simple).
--
--   audit_chain_config : une seule ligne, écrite une fois, jamais modifiée ni supprimée (déclencheur).
--     hmac_depuis_position          : première position de la chaîne calculée en HMAC (avant : SHA-256 simple).
--     cle_alias / cle_empreinte     : alias de la clé HMAC et son empreinte de contrôle (HMAC d'un libellé fixe) —
--                                     distingue "mauvaise clé / clé absente" d'une vraie falsification.
--     scellements_signes_depuis_id  : premier scellement qui DOIT porter une signature (les précédents datent d'avant).
--
--   audit_chain_seals : chaque scellement porte désormais la position de la dernière entrée scellée, l'empreinte du
--     scellement précédent (retirer un scellement au milieu rompt ce lien) et la signature de la clé système.

CREATE TABLE IF NOT EXISTS audit_chain_config (
    id                            bigint      PRIMARY KEY CHECK (id = 1),
    hmac_depuis_position          bigint      NOT NULL,
    cle_alias                     varchar(100) NOT NULL,
    cle_empreinte                 varchar(64) NOT NULL,
    scellements_signes_depuis_id  bigint      NOT NULL,
    created_at                    timestamptz NOT NULL
);

CREATE OR REPLACE FUNCTION proteger_audit_chain_config() RETURNS trigger AS $fn$
BEGIN
    RAISE EXCEPTION 'Modification interdite de la configuration de la chaîne d''audit'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_audit_chain_config ON audit_chain_config;
CREATE TRIGGER trg_proteger_audit_chain_config
    BEFORE UPDATE OR DELETE ON audit_chain_config
    FOR EACH ROW EXECUTE FUNCTION proteger_audit_chain_config();

ALTER TABLE audit_chain_seals ADD COLUMN IF NOT EXISTS dernier_position_chaine bigint;
ALTER TABLE audit_chain_seals ADD COLUMN IF NOT EXISTS empreinte_precedente    varchar(64);
ALTER TABLE audit_chain_seals ADD COLUMN IF NOT EXISTS signature               text;
ALTER TABLE audit_chain_seals ADD COLUMN IF NOT EXISTS signature_alias         varchar(100);
