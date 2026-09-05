#!/bin/sh
# Régénère dynamic.yml depuis dynamic.yml.template à CHAQUE démarrage du
# conteneur, en substituant ${APP_DOMAIN} par la vraie valeur (variable
# d'environnement du conteneur, définie dans docker-compose.yml depuis .env).
#
# Automatique, pas une étape à se souvenir de lancer soi-même : ce script
# tourne à chaque "docker compose up", et Compose recrée de toute façon ce
# conteneur dès que .env change (comportement normal, déjà vrai pour tous les
# autres services) — donc un changement d'APP_DOMAIN se propage ici sans
# aucune action supplémentaire. Voir DEPLOYMENT.md.
#
# "sed" plutôt que "envsubst" : envsubst (paquet gettext) n'est pas présent
# dans l'image traefik officielle (basée sur une image minimale) — sed, lui,
# est déjà disponible (l'entrypoint d'origine de l'image, /entrypoint.sh,
# est lui-même un script shell qui en a besoin).
set -eu

if [ -z "${APP_DOMAIN:-}" ]; then
    echo "ERREUR : la variable d'environnement APP_DOMAIN n'est pas définie (voir .env)." >&2
    exit 1
fi

TEMPLATE=/etc/traefik/dynamic-template/dynamic.yml.template
DEST=/etc/traefik/dynamic/dynamic.yml

mkdir -p "$(dirname "$DEST")"
sed "s|\${APP_DOMAIN}|${APP_DOMAIN}|g" "$TEMPLATE" > "$DEST"

echo "traefik/dynamic.yml régénéré pour APP_DOMAIN=${APP_DOMAIN}"

# Relais vers le VRAI point d'entrée de l'image traefik officielle, avec les
# arguments originaux (la commande définie dans docker-compose.yml)
# inchangés.
exec /entrypoint.sh "$@"
