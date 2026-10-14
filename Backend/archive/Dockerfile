FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Ghostscript : conversion en PDF/A des PDF importés tels quels (voir
# PdfAConversionService) — les polices fonts-* ci-dessous lui servent aussi de
# substituts pour les polices non intégrées dans le PDF d'origine.
# SoftHSM : HSM logiciel libre (interface PKCS#11) qui garde les clés de signature des éditeurs, la clé système et la
# clé de chaînage du journal d'audit — voir HsmKeyStoreService et docker-entrypoint.sh (initialisation du jeton).
# Tesseract + langues (LibreOffice n'est plus installé ici — la conversion
# PDF passe désormais par le conteneur Gotenberg, voir docker-compose.yml)
# Version non épinglée : suit les dépôts de la distro (Ubuntu 22.04 "jammy",
# verrouillée par l'image de base ci-dessus). La ligne "tesseract --version"
# ci-dessous rend la version réellement installée visible dans les logs de
# build à chaque reconstruction, pour détecter toute dérive.
RUN apt-get update && apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-fra \
    tesseract-ocr-eng \
    tesseract-ocr-ara \
    fonts-liberation \
    fonts-dejavu \
    ghostscript \
    softhsm2 \
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --version \
    && gs --version \
    && softhsm2-util --version

COPY build/libs/*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/app/docker-entrypoint.sh"]