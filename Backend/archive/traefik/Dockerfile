# Image Traefik "maison" : identique à l'image officielle traefik:v3.2, avec
# un seul ajout — docker-entrypoint.sh, qui régénère dynamic.yml depuis
# dynamic.yml.template à chaque démarrage (voir ce script pour le détail et
# le pourquoi). Construite comme les images "app"/"frontend" : publiée sur
# ghcr.io pour un déploiement sans code source (voir DEPLOYMENT.md), et
# reconstruite localement en développement (docker-compose.override.yml).
FROM traefik:v3.2

COPY docker-entrypoint.sh /docker-entrypoint-app.sh
COPY dynamic.yml.template /etc/traefik/dynamic-template/dynamic.yml.template

ENTRYPOINT ["/docker-entrypoint-app.sh"]
