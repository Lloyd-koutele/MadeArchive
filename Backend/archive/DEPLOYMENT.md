# Déploiement de MadeArchive

Guide pour déployer l'application sur un serveur (VPS AWS, Azure, ou
équivalent) via Docker Compose. Voir `.env.example` pour le détail de
chaque variable de configuration.

## 1. Prérequis

- **Un VPS Linux** avec Docker et Docker Compose installés.
  - **RAM : viser au moins 16 Go.** Ollama seul consomme couramment
    4,5-5,5 Go (modèle chargé) ; avec le reste de la pile (Postgres, MinIO,
    Meilisearch, Redis, Gotenberg, Chromium headless, l'application elle-même),
    un serveur à 4-8 Go sera insuffisant.
- **Un nom de domaine réel**, avec un enregistrement DNS de type A pointant
  vers l'IP publique du VPS.
- **Ports 80 et 443 ouverts** dans le pare-feu/groupe de sécurité du VPS
  (AWS Security Group, Azure NSG) — sans ça, Traefik ne peut ni recevoir de
  trafic ni obtenir de certificat Let's Encrypt (défi HTTP-01, qui a besoin
  du port 80 joignable publiquement).
- **Tous les autres ports fermés au public** — MinIO, Meilisearch, Ollama,
  Redis, Gotenberg, Chromium et PostgreSQL ne doivent JAMAIS être exposés
  directement à Internet (voir section 5, Sécurité).

## 2. Récupérer le projet

```bash
git clone <url-du-dépôt>
cd MadeArchive/Backend/archive
```

## 3. Configurer `.env`

```bash
cp .env.example .env
```

Éditez `.env` et remplacez **chaque** valeur marquée "À CHANGER" — jamais
les exemples du fichier tels quels. Génération des secrets recommandée :

```bash
openssl rand -base64 32   # pour la plupart des clés
openssl rand -hex 24      # pour CHROMIUM_TOKEN
```

Deux catégories de variables dans `.env` :
- **À changer par vous** (mots de passe, clés, domaine, email) — voir les
  commentaires "À CHANGER" dans `.env.example`.
- **À ne pas toucher pour un usage Docker** (`DB_URL`, `MINIO_URL`,
  `MEILISEARCH_HOST`, `OLLAMA_BASE_URL`, `TESSERACT_DATA_PATH`,
  `HSM_KEYSTORE_PATH`) — `docker-compose.yml` les surcharge déjà vers les
  noms de service internes (`postgres`, `minio`, etc.) ; les valeurs du
  `.env` ne servent qu'à un usage natif hors Docker (`./gradlew bootRun`).

## 4. Configurer le domaine dans Traefik

Traefik (mode "fournisseur fichier") ne peut pas lire les variables de
`docker-compose.yml` — le domaine doit être écrit en clair, séparément,
dans `traefik/dynamic.yml` :

```bash
sed -i 's/madearchive\.sn/votredomaine.com/g' traefik/dynamic.yml
```

(remplacez `madearchive.sn` par le domaine que vous avez réellement utilisé
en local, si différent — ou éditez directement les deux occurrences de
`Host(\`...\`)` dans le fichier). Ce nom doit être **identique** à
`FRONTEND_URL` dans `.env`.

## 5. Sécurité — à vérifier avant de démarrer

Dans `docker-compose.yml`, chaque service interne (MinIO, Meilisearch,
Ollama, Redis, Gotenberg, Chromium, PostgreSQL) publie actuellement son
port sur `0.0.0.0` — pratique en local, **dangereux sur un serveur avec IP
publique**. Avant un vrai déploiement :

- Soit **retirer complètement** le bloc `ports:` de ces services (le réseau
  Docker interne suffit à `app` pour les joindre, aucune publication
  nécessaire) ;
- Soit les restreindre à `127.0.0.1:PORT:PORT` (accessible uniquement
  depuis le VPS lui-même, via tunnel SSH si besoin d'y accéder depuis votre
  poste).

Seuls les ports **80 et 443** (Traefik) doivent rester publiés sur
`0.0.0.0`.

## 6. Construire et démarrer

Le `Dockerfile` du backend attend un `.jar` déjà compilé (pas encore
converti en build multi-étapes comme celui du frontend) :

```bash
./gradlew bootJar -x test
docker compose up -d --build
```

Vérifiez que tout est sain :

```bash
docker compose ps
```

Tous les services doivent afficher "Up" (et "healthy" pour ceux qui ont un
healthcheck).

## 7. Premier lancement — créer l'administrateur

Deux façons, au choix (voir `.env.example`, section "Admin initial") :
- **Automatique** : renseigner `INITIAL_ADMIN_EMAIL`/`PASSWORD`/etc. dans
  `.env` avant le premier démarrage.
- **Interactive** : les laisser vides — un assistant s'affiche à l'accueil
  de l'application tant qu'aucun administrateur n'existe.

Les deux voies se ferment définitivement dès qu'un admin existe, peu
importe laquelle l'a créé.

## 8. Vérifications post-déploiement

```bash
curl -I http://votredomaine.com     # doit rediriger (301/308) vers https://
```

Consultez `https://votredomaine.com` dans un navigateur — le certificat
doit être un vrai certificat Let's Encrypt (pas d'avertissement), à
condition que le DNS pointe déjà vers le VPS et que le port 80 soit
joignable publiquement au moment du premier démarrage de Traefik.

## 9. Mettre à jour un déploiement existant

Point souvent mal compris : **pousser du code sur GitHub ne met à jour
ni les images Docker déjà publiées, ni un serveur déjà déployé.** Ce sont
deux artefacts séparés :

| Artefact | Où il vit | Mis à jour par |
|---|---|---|
| Code source (`.java`, `.tsx`, etc.) | dépôt GitHub | `git push` |
| Images construites (`app`, `frontend`) | `ghcr.io/lloyd-koutele/...` | `.github/workflows/publish-images.yml` — **déclenchement manuel uniquement** (`workflow_dispatch`, onglet Actions → "Run workflow"), volontairement : pour garder le contrôle explicite de quand une nouvelle image devient disponible. Un `git push` seul ne la reconstruit jamais. |

Concrètement, selon le type de correctif :

**a) Correctif dans le code Java/React (backend ou frontend)** — vit
*dans* l'image, invisible pour quelqu'un qui n'a que `docker-compose.yml`
+ `.env` tant que l'image n'est pas republiée :
1. Pousser le code sur GitHub (`git push`).
2. Déclencher manuellement `publish-images.yml` (onglet Actions du
   dépôt → "Publier les images (ghcr.io)" → "Run workflow").
3. Sur le serveur, récupérer la nouvelle image et redémarrer :
   ```bash
   docker compose pull
   docker compose up -d
   ```

**b) Correctif dans un fichier de configuration** (`docker-compose.yml`,
`traefik/dynamic.yml`, `.env.example`) — ces fichiers ne sont **jamais**
intégrés à une image : Traefik lit `dynamic.yml` directement depuis le
disque (monté en volume), et `docker-compose.yml` est lui-même le fichier
d'orchestration. `docker compose pull` ne les touche donc jamais, même
après republication d'image. Il faut récupérer le(s) fichier(s) mis à
jour explicitement, par exemple :
```bash
git pull                        # si le serveur a cloné le dépôt entier
# — ou, si vous ne suivez que docker-compose.yml + .env (sans le code
#   source) — retélécharger uniquement les fichiers changés (interface
#   GitHub, "Raw", ou git sparse-checkout).
docker compose up -d            # relit docker-compose.yml, recrée les
                                 # conteneurs dont la config a changé
docker compose restart traefik  # si seul traefik/dynamic.yml a changé
                                 # (Traefik recharge à chaud, un simple
                                 # restart suffit à forcer la relecture)
```

**Cas particulier — certificats mkcert** (`traefik/certs/*.pem`) :
volontairement **absents du dépôt Git** (`.gitignore`) et **spécifiques
à chaque machine** — ils ne sont trustés que là où `mkcert -install` a
été exécuté. Ils ne font partie d'aucun flux de mise à jour : un vrai
déploiement avec domaine public utilise Let's Encrypt (`certResolver`,
voir section 4), pas mkcert. Ce mécanisme ne concerne que les
répétitions/tests en local (VM Multipass, poste de développement).

## Limites connues (axes d'évolution, pas des blocages)

- **Volumes anonymes** pour MinIO et Meilisearch — les documents archivés
  et l'index de recherche ne survivent pas à un `docker compose down`
  fait sans y penser. PostgreSQL utilise déjà un volume nommé.
- **Aucune sauvegarde automatisée** (base de données, fichiers MinIO) — à
  mettre en place séparément selon votre fournisseur.
- **Pas de chaîne de déploiement continu** — le déploiement reste manuel
  (`docker compose up -d --build`), seuls les tests sont automatisés (voir
  `.github/workflows/`).
- **Dockerfile backend à build externe** — nécessite `./gradlew bootJar`
  avant chaque `docker compose up --build` ; un oubli redéploie
  silencieusement l'ancien code.
