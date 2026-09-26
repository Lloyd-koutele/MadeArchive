// Fichier temporaire, uniquement pour prévisualiser le site dans le
// navigateur intégré (qui rejette le certificat auto-signé HTTPS du
// serveur de dev habituel). Pas de HTTPS ici -> pas destiné à être commité,
// à supprimer après la session de test.
import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: { host: true, port: 5180 },
})
