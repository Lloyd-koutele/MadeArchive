-- Journal de cycle de vie d'un document (voir service.document.DocumentJournalService) :
-- il relit journal_audit par (cible_type, cible_id) à chaque ouverture du journal d'un
-- document — sans index, une lecture complète de la table à chaque fois.
CREATE INDEX IF NOT EXISTS idx_audit_cible ON journal_audit (cible_type, cible_id);
