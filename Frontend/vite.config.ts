import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import basicSsl from '@vitejs/plugin-basic-ssl'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    // Certificat auto-signé généré à la volée — le navigateur affichera un
    // avertissement (normal, pas une vraie autorité de certification) mais
    // le serveur de dev répond bien en HTTPS. Cohérent avec le backend
    // (derrière Traefik) qui accepte lui aussi HTTPS en local via un
    // certificat auto-signé tant qu'aucun vrai domaine public n'est configuré.
    basicSsl(),
  ],
  server:{
    host: true,
    https: true,
  }
})
