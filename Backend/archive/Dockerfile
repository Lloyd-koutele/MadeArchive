FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

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
    && rm -rf /var/lib/apt/lists/* \
    && tesseract --version

COPY build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]