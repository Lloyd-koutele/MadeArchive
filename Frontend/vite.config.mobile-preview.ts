// Fichier temporaire, uniquement pour prévisualiser le site dans le
// navigateur intégré (qui rejette le certificat auto-signé HTTPS du
// serveur de dev habituel). Pas de HTTPS ici -> pas destiné à être commité,
// à supprimer après la session de test.
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { host: true, port: 5180 },
})
