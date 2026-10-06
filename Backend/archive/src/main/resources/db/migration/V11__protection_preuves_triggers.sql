-- Protection en base des preuves d'intégrité : une fois écrites, elles ne peuvent plus changer.
--
-- Ces déclencheurs s'appliquent à TOUS les rôles, y compris celui de l'application : une écriture
-- directe (SQL injecté, compte de base compromis non superutilisateur, console d'administration)
-- échoue au lieu de réécrire silencieusement une empreinte. Un superutilisateur peut encore les
-- désactiver (ALTER TABLE ... DISABLE TRIGGER) — c'est pourquoi la vérification cryptographique
-- (signature HSM, jetons RFC 3161, racines de Merkle) reste la défense de fond, indépendante de la
-- base. Les déclencheurs ne sont qu'une première barrière, peu coûteuse.
--
-- "Écrite une fois" : une colonne nulle peut recevoir sa valeur (horodatage obtenu après coup,
-- scellement d'un document ancien) ; une fois renseignée, elle est figée.

CREATE OR REPLACE FUNCTION proteger_preuves_documents() RETURNS trigger AS $fn$
BEGIN
    IF NEW.original_sha256 IS DISTINCT FROM OLD.original_sha256
       OR NEW.pdfa_sha256 IS DISTINCT FROM OLD.pdfa_sha256
       OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
       OR NEW.uo_id       IS DISTINCT FROM OLD.uo_id
       OR NEW.version     IS DISTINCT FROM OLD.version
       OR NEW.create_at   IS DISTINCT FROM OLD.create_at THEN
        RAISE EXCEPTION 'Modification interdite d''une preuve d''intégrité (document %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF (OLD.pki_signature IS NOT NULL AND NEW.pki_signature IS DISTINCT FROM OLD.pki_signature)
       OR (OLD.signature_enregistrement IS NOT NULL
           AND NEW.signature_enregistrement IS DISTINCT FROM OLD.signature_enregistrement)
       OR (OLD.signature_enregistrement_alias IS NOT NULL
           AND NEW.signature_enregistrement_alias IS DISTINCT FROM OLD.signature_enregistrement_alias)
       OR (OLD.horodatage_token IS NOT NULL AND NEW.horodatage_token IS DISTINCT FROM OLD.horodatage_token)
       OR (OLD.horodatage_date IS NOT NULL AND NEW.horodatage_date IS DISTINCT FROM OLD.horodatage_date)
       OR (OLD.ancrage_id IS NOT NULL AND NEW.ancrage_id IS DISTINCT FROM OLD.ancrage_id) THEN
        RAISE EXCEPTION 'Modification interdite d''une preuve d''intégrité déjà écrite (document %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_preuves_documents ON documents;
CREATE TRIGGER trg_proteger_preuves_documents
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION proteger_preuves_documents();

-- Journal d'audit : seules les colonnes de chaînage peuvent être renseignées, une seule fois.
CREATE OR REPLACE FUNCTION proteger_journal_audit() RETURNS trigger AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Suppression interdite dans le journal d''audit (entrée %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF to_jsonb(NEW) - 'chain_hash' - 'position_chaine'
       IS DISTINCT FROM to_jsonb(OLD) - 'chain_hash' - 'position_chaine'
       OR (OLD.chain_hash IS NOT NULL AND NEW.chain_hash IS DISTINCT FROM OLD.chain_hash)
       OR (OLD.position_chaine IS NOT NULL AND NEW.position_chaine IS DISTINCT FROM OLD.position_chaine) THEN
        RAISE EXCEPTION 'Modification interdite dans le journal d''audit (entrée %)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_journal_audit ON journal_audit;
CREATE TRIGGER trg_proteger_journal_audit
    BEFORE UPDATE OR DELETE ON journal_audit
    FOR EACH ROW EXECUTE FUNCTION proteger_journal_audit();

-- Scellements du journal et ancrages du catalogue : ajout seul. Seul l'horodatage (jeton, date,
-- transaction blockchain) peut être complété après coup, une seule fois.
CREATE OR REPLACE FUNCTION proteger_scellements() RETURNS trigger AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Suppression interdite (table %, ligne %)', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF to_jsonb(NEW) - 'horodatage_token' - 'horodatage_date' - 'blockchain_tx'
       IS DISTINCT FROM to_jsonb(OLD) - 'horodatage_token' - 'horodatage_date' - 'blockchain_tx'
       OR (OLD.horodatage_token IS NOT NULL AND NEW.horodatage_token IS DISTINCT FROM OLD.horodatage_token)
       OR (OLD.horodatage_date IS NOT NULL AND NEW.horodatage_date IS DISTINCT FROM OLD.horodatage_date) THEN
        RAISE EXCEPTION 'Modification interdite (table %, ligne %)', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_proteger_audit_chain_seals ON audit_chain_seals;
CREATE TRIGGER trg_proteger_audit_chain_seals
    BEFORE UPDATE OR DELETE ON audit_chain_seals
    FOR EACH ROW EXECUTE FUNCTION proteger_scellements();

DROP TRIGGER IF EXISTS trg_proteger_ancrages_catalogue ON ancrages_catalogue;
CREATE TRIGGER trg_proteger_ancrages_catalogue
    BEFORE UPDATE OR DELETE ON ancrages_catalogue
    FOR EACH ROW EXECUTE FUNCTION proteger_scellements();
