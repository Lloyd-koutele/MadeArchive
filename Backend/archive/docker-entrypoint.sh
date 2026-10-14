#!/bin/sh
# Démarrage de l'application : initialise le jeton SoftHSM (HSM logiciel, PKCS#11) au PREMIER lancement, puis démarre.
# Le jeton vit sur un volume persistant (/var/lib/softhsm/tokens) : les clés survivent aux redéploiements.
# HSM_TYPE=PKCS12 (repli sur un fichier keystore) : rien à initialiser.
set -e

if [ "${HSM_TYPE:-PKCS11}" = "PKCS11" ]; then
    : "${HSM_PIN:?HSM_PIN doit être défini (code PIN du jeton SoftHSM) — voir .env.example}"
    : "${HSM_SO_PIN:?HSM_SO_PIN doit être défini (code PIN administrateur du jeton SoftHSM) — voir .env.example}"
    ETIQUETTE="${HSM_TOKEN_LABEL:-madearchive}"

    if ! softhsm2-util --show-slots | grep -q "Label: *${ETIQUETTE} *$"; then
        echo "[entrypoint] Initialisation du jeton SoftHSM '${ETIQUETTE}'"
        softhsm2-util --init-token --free --label "${ETIQUETTE}" --so-pin "${HSM_SO_PIN}" --pin "${HSM_PIN}"
    else
        echo "[entrypoint] Jeton SoftHSM '${ETIQUETTE}' déjà initialisé"
    fi
fi

exec java -jar app.jar
