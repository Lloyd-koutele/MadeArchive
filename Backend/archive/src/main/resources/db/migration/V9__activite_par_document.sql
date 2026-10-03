-- Activité (plan de classement) propre à UN document : exception à l'activité par défaut de son type.
-- Null = le document suit l'activité de son type (cas général, et tous les documents existants) ;
-- renseignée = activité choisie pour ce document précis (ex. facture ponctuelle dans un type
-- habituellement récurrent). Voir Document.planClassementNoeud et PlanClassementService.activiteEffective.

ALTER TABLE documents ADD COLUMN IF NOT EXISTS plan_classement_noeud_id bigint REFERENCES plan_classement_noeuds (id);
CREATE INDEX IF NOT EXISTS idx_documents_plan_classement ON documents (plan_classement_noeud_id) WHERE plan_classement_noeud_id IS NOT NULL;
