-- Chaînage du journal d'audit en continu (toutes les quelques secondes au lieu d'une fois par nuit)
-- et scellement RFC 3161 séparé (toutes les 15 minutes) — voir service.audit.AuditChainService.
--
--   position_chaine : rang de l'entrée DANS LA CHAÎNE, attribué par le job de chaînage au moment où il
--     la traite — et non plus l'id. Un id est attribué à l'INSERTION, pas au COMMIT : une transaction
--     lente peut valider l'entrée 100 après que l'entrée 101 a déjà été chaînée. Avec un chaînage
--     nocturne c'était quasi impossible ; toutes les 10 secondes ça arriverait, et une vérification
--     relisant par id signalerait une fausse rupture. La vérification suit désormais cette colonne.
--
-- Reprise de l'existant : la chaîne déjà calculée l'a été par id croissant, donc les positions sont
-- attribuées dans ce même ordre — toutes les empreintes déjà calculées restent valides, rien n'est
-- recalculé. Les entrées jamais chaînées gardent une position nulle.

ALTER TABLE journal_audit ADD COLUMN IF NOT EXISTS position_chaine bigint;

UPDATE journal_audit j
SET position_chaine = s.rang
FROM (SELECT id, row_number() OVER (ORDER BY id) AS rang
      FROM journal_audit
      WHERE chain_hash IS NOT NULL) s
WHERE j.id = s.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_journal_audit_position_chaine ON journal_audit (position_chaine);

-- Le job de chaînage cherche les entrées pas encore chaînées toutes les 10 secondes : index partiel,
-- minuscule (il ne contient que les quelques entrées en attente), pour ne jamais parcourir la table.
CREATE INDEX IF NOT EXISTS idx_journal_audit_a_chainer ON journal_audit (id) WHERE chain_hash IS NULL;
