-- Ordre des champs de métadonnées d'un type de document : choisi par l'éditeur (glisser-déposer), conservé en base.
-- Jusqu'ici l'ordre était celui de l'identifiant (ordre de création) et ne pouvait pas être changé après coup.
ALTER TABLE meta_datas ADD COLUMN ordre integer NOT NULL DEFAULT 0;

UPDATE meta_datas m
   SET ordre = r.rang
  FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY type_document_id ORDER BY id) - 1 AS rang FROM meta_datas) r
 WHERE m.id = r.id;
