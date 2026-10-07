-- Empreinte de la clé de chiffrement au repos (STORAGE_ENCRYPTION_KEY).
--
-- Sert à distinguer "la clé fournie à l'application n'est plus celle des archives" (erreur d'exploitation :
-- .env régénéré, mauvais secret, clé manquante) de "ce fichier a été altéré". Sans cette référence, un
-- déchiffrement raté faisait basculer le document en CORROMPU — et une clé erronée aurait donc fait basculer
-- TOUTES les archives d'un coup. Seule une empreinte à sens unique (HMAC-SHA256 d'un libellé fixe, avec la clé)
-- est stockée : elle ne permet pas de retrouver la clé.
--
-- Une seule ligne, écrite une fois, jamais modifiée ni supprimée (déclencheur, pour TOUS les rôles). Changer
-- volontairement de clé est une opération administrée (procédure de rotation), pas une écriture applicative.

CREATE TABLE IF NOT EXISTS cle_chiffrement_reference (
    id         bigint      PRIMARY KEY CHECK (id = 1),
    empreinte  varchar(64) NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE OR REPLACE FUNCTION proteger_cle_chiffrement_reference() RETURNS trigger AS $fn$
BEGIN
    RAISE EXCEPTION 'Modification interdite de la référence de la clé de chiffrement'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_cle_chiffrement_reference ON cle_chiffrement_reference;
CREATE TRIGGER trg_proteger_cle_chiffrement_reference
    BEFORE UPDATE OR DELETE ON cle_chiffrement_reference
    FOR EACH ROW EXECUTE FUNCTION proteger_cle_chiffrement_reference();
