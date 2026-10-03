-- Fin de vie des documents : motif de suppression, blocage de l'élimination, alerte avant suppression
-- automatique, et délai de grâce propre à chaque type (Retention.periodGrace, enfin utilisé).
--
--   motif_suppression / commentaire_suppression : pourquoi le document est en corbeille
--     (ERREUR_ARCHIVAGE, SUPPRESSION_LEGALE, AUTRE + commentaire obligatoire, FIN_DE_VIE = mis par le
--     système à l'échéance de conservation). Conservés dans la pierre tombale après la purge.
--   elimination_bloquee + blocage_* : l'éditeur peut bloquer la suppression automatique d'un document
--     en corbeille (motif, auteur et date conservés).
--   alerte_suppression_le : dernier jour où l'alerte "suppression imminente" a été envoyée (une par jour).

ALTER TABLE documents ADD COLUMN IF NOT EXISTS motif_suppression        varchar(30);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS commentaire_suppression  varchar(500);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS alerte_suppression_le    date;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS elimination_bloquee      boolean NOT NULL DEFAULT false;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS blocage_motif            varchar(500);
ALTER TABLE documents ADD COLUMN IF NOT EXISTS blocage_par              uuid;
ALTER TABLE documents ADD COLUMN IF NOT EXISTS blocage_le               timestamptz;

-- Reprise des documents déjà en corbeille : ceux que le SYSTÈME y a mis à l'échéance (journal :
-- DOCUMENT_PLACE_CORBEILLE sans acteur) reçoivent le motif FIN_DE_VIE. Les autres (suppression
-- volontaire) restent sans motif ("non renseigné").
UPDATE documents d SET motif_suppression = 'FIN_DE_VIE'
WHERE d.status = 'CORBEILLE'
  AND EXISTS (SELECT 1 FROM journal_audit j
              WHERE j.cible_type = 'DOCUMENT' AND j.cible_id = d.id::text
                AND j.action = 'DOCUMENT_PLACE_CORBEILLE' AND j.acteur_id IS NULL);

-- Aucune purge ne doit être déclenchée par cette migration : tout document en corbeille de type
-- CONSERVER/TRIER dont l'échéance est déjà passée (que la nouvelle règle rendrait purgeable s'il
-- avait été supprimé volontairement) repart avec un délai de grâce complet.
UPDATE documents d SET suppression_prevue_le = CURRENT_DATE + 6
WHERE d.status = 'CORBEILLE'
  AND d.suppression_prevue_le <= CURRENT_DATE
  AND EXISTS (SELECT 1 FROM type_documents t JOIN retentions r ON r.id = t.retention_id
              WHERE t.id = d.type_document_id AND r.sort_final <> 'DETRUIRE');

-- period_grace valait 30 par défaut mais n'était lu nulle part : valeur sans signification, remise à
-- vide (= délai par défaut de 6 jours) — désormais saisi à la création du type.
UPDATE retentions SET period_grace = NULL;
