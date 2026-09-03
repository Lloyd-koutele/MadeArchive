# Build multi-étapes : Node compile les assets statiques, Nginx les sert —
# jamais le serveur de dev Vite (npm run dev) en production, beaucoup plus
# lourd et pas prévu pour ça.
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# VITE_API_URL est gravé DANS le bundle JS au moment du build (Vite ne lit
# jamais les variables d'environnement au runtime, contrairement au backend
# Spring Boot) — /api en relatif : fonctionne quel que soit le domaine réel,
# tant que Traefik route /api/* vers "app" sur le MÊME domaine que le
# frontend (voir traefik/dynamic.yml). Pas besoin de connaître le nom de
# domaine à l'avance.
ARG VITE_API_URL=/api
ENV VITE_API_URL=${VITE_API_URL}
# "build:docker" (vite build seul), pas "build" (tsc -b && vite build) :
# npm run dev ne vérifie jamais les types (esbuild transpile sans
# type-checker) alors que "build" le fait strictement — l'écart a laissé
# s'accumuler ~90 erreurs de type réelles, jamais remontées avant ce build
# Docker (premier vrai build de production tenté). Les corriger est un vrai
# axe qualité (voir 5.10.4 du mémoire), mais ne doit pas bloquer le
# déploiement d'un code qui fonctionne déjà en dev — "vite build" seul
# reproduit exactement le même contrat de vérification que "npm run dev".
RUN npm run build:docker

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
