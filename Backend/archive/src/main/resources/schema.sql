CREATE UNIQUE INDEX IF NOT EXISTS uk_membre_uo_user_actif
ON membres_uo (user_id)
WHERE actif = true;