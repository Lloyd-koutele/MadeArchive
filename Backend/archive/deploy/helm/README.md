# Déploiement multi-société — un namespace par société

Correspond au schéma [Silo K8s](../../..) discuté : chaque société installe
sa propre copie complète de `madearchive/` dans son propre namespace ; un
seul `shared-services/` sert Gotenberg et Ollama à tout le cluster, puisque
ce sont les seuls services qui ne retiennent aucune donnée cliente.

```
deploy/helm/
├── madearchive/        # 1 install = 1 société = 1 namespace isolé
└── shared-services/     # 1 install pour tout le cluster
```

## Prérequis — à faire une seule fois pour le cluster

```bash
# Ingress controller (si pas déjà présent)
helm install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace

# Services partagés sans état
helm install shared shared-services -n shared-services --create-namespace
```

## Onboarder une nouvelle société

1. **Générer son keystore PKI hors bande** (jamais via ce chart — voir
   `values.yaml` de `madearchive/`, section `hsm`) :
   ```bash
   kubectl create namespace societe-x
   kubectl create secret generic societe-x-hsm -n societe-x \
     --from-file=editors-keystore.p12=./editors-keystore.p12 \
     --from-literal=keystore-password='<mot de passe réel>'
   ```

2. **Installer la pile** :
   ```bash
   helm install societe-x madearchive -n societe-x \
     --set societe.nom=SocieteX \
     --set ingress.host=societe-x.madearchive.com \
     --set app.frontendUrl=https://societe-x.madearchive.com \
     --set hsm.existingSecretName=societe-x-hsm \
     --set image.repository=ghcr.io/votre-org/madearchive-app \
     --set image.tag=1.0.0
   ```

   `STORAGE_ENCRYPTION_KEY`, le mot de passe Postgres, les credentials MinIO,
   la clé Meilisearch et le secret JWT sont générés automatiquement au
   premier install (voir `templates/secrets.yaml`) — vous n'avez rien à
   fournir pour ceux-là.

3. **Vérifier** :
   ```bash
   kubectl get pods -n societe-x
   kubectl get networkpolicy -n societe-x
   ```

## Mettre à jour une société (nouvelle version de l'appli)

```bash
helm upgrade societe-x madearchive -n societe-x --set image.tag=1.1.0
```

`secrets.yaml` relit les valeurs déjà en place via `lookup` — **aucun**
secret n'est régénéré à l'upgrade, `STORAGE_ENCRYPTION_KEY` en tête (le
régénérer rendrait tous les documents déjà archivés indéchiffrables,
définitivement). Pour vérifier vous-même que ça tient avant de faire
confiance à ce mécanisme sur un cluster réel :

```bash
kubectl get secret societe-x-secrets -n societe-x -o jsonpath='{.data.STORAGE_ENCRYPTION_KEY}' > /tmp/avant
helm upgrade societe-x madearchive -n societe-x --set image.tag=1.1.0
kubectl get secret societe-x-secrets -n societe-x -o jsonpath='{.data.STORAGE_ENCRYPTION_KEY}' > /tmp/apres
diff /tmp/avant /tmp/apres && echo "OK — identique"
```

## Désabonner une société

```bash
helm uninstall societe-x -n societe-x
kubectl delete namespace societe-x   # purge PVC/Secrets restants
```

## Simplifications assumées dans ce squelette — à durcir avant la production

- **Postgres et MinIO en 1 replica**, sans haute disponibilité ni bascule
  automatique. Pour une vraie HA, remplacer le `StatefulSet` Postgres par un
  opérateur (ex. CloudNativePG) en gardant le même nom de `Service`.
- **Probes de l'appli en `tcpSocket`**, pas en `httpGet` — `build.gradle` n'a
  pas encore `spring-boot-starter-actuator`. À corriger dès qu'il est ajouté
  (un port ouvert ne dit rien de l'état réel de l'appli).
- **`STORAGE_ENCRYPTION_KEY` remplace une clé unique globale** — c'est le
  point de départ de toute cette architecture (voir la conversation
  d'origine) : chaque société a désormais la sienne, générée à l'install et
  jamais régénérée.
- **Le wallet Polygon** (`values.yaml`, `polygon.*`) n'est jamais généré
  automatiquement — une clé privée auto-générée sans fonds ne sert à rien.
  Décidez d'abord si l'ancrage blockchain est par société ou partagé côté
  plateforme avant de remplir ces champs.
- **Pas de cluster K8s disponible dans cet environnement pour un
  `kubectl apply --dry-run=server` réel** — seuls `helm lint` et
  `helm template` ont pu être exécutés (les deux passent sans erreur).
  Faites un dry-run serveur sur un cluster de test avant la première
  société en production.
