#!/usr/bin/env python3
"""
Exporte en clair, sur disque, des documents choisis — par UO (une ou
plusieurs, au choix, sans obligation de prendre tous les enfants), et par
document (un seul document précis si besoin, sans prendre tout le groupe).
Organisés en dossiers séparés par UO puis, en option, par projet.

Interface graphique par défaut (Tkinter, inclus avec Python — aucune
dépendance GUI à installer). Mode ligne de commande toujours disponible en
passant des arguments.

Quatre étapes, dans l'ordre : (1) la base répond "quelles UO existent" pour
qu'on choisisse les noms exacts voulus, (2) la base répond "quels documents
correspondent" pour qu'on choisisse lesquels garder, (3) MinIO répond
"voici les octets chiffrés" pour chaque document retenu, (4) ce script
déchiffre chaque objet avec STORAGE_ENCRYPTION_KEY. MinIO n'a jamais eu de
notion d'UO ni de projet — ce script EST le suivi document → UO → projet,
reconstruit au moment de l'export à partir de la base, jamais stocké
ailleurs.

Dépendances externes :
  - `psql` sur le PATH
  - `mc` (MinIO client) sur le PATH — `brew install minio/stable/mc`
  - le paquet Python `cryptography` — `pip install cryptography`
  - Tkinter (mode GUI seulement — inclus avec la plupart des installations
    Python ; sur certaines distributions Linux : `apt install python3-tk`)

Configuration : lue automatiquement depuis un fichier `.env` (celui du
programme dans lequel ce script est placé — voir find_env_file()). Rien à
exporter en variable d'environnement à la main : placez ce script à côté du
`.env` de la société dont vous voulez exporter les documents, et lancez-le.

Usage GUI (par défaut) :
    python3 export_uo_documents.py

Usage CLI :
    python3 export_uo_documents.py --list-uos
    python3 export_uo_documents.py --uo-noms Ucad,Esp --separate-projects \
        --output-dir ./export
    python3 export_uo_documents.py --uo-noms Ucad --include-children \
        --output-dir ./export
    python3 export_uo_documents.py --uo-noms Ucad --list-documents
    python3 export_uo_documents.py --uo-noms Ucad \
        --doc-ids 49cbb9b9-5423-48e4-bfce-053507f10a44 --output-dir ./un-seul
"""
import argparse
import base64
import os
import re
import subprocess
import sys
import threading
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("Le paquet 'cryptography' est requis : pip install cryptography")


# ─────────────────────────────────────────────────────────────────────────
# .env — auto-détection et lecture, aucune variable d'environnement requise
# ─────────────────────────────────────────────────────────────────────────

def find_env_file(explicit_path: str | None = None) -> Path:
    if explicit_path:
        p = Path(explicit_path)
        if not p.is_file():
            raise FileNotFoundError(f"Fichier .env introuvable : {p}")
        return p
    candidats = [Path(__file__).resolve().parent / ".env", Path.cwd() / ".env"]
    for c in candidats:
        if c.is_file():
            return c
    raise FileNotFoundError(
        "Aucun fichier .env trouvé à côté du script ni dans le dossier courant. "
        "Placez ce script au même niveau que le .env du programme concerné, "
        "ou indiquez son chemin explicitement (--env-file / bouton Parcourir)."
    )


def parse_env_file(path: Path) -> dict:
    env = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip().strip('"').strip("'")
        env[key.strip()] = value
    return env


def parse_jdbc_url(jdbc_url: str) -> tuple[str, str, str]:
    """'jdbc:postgresql://host:port/db' -> (host, port, db)."""
    m = re.match(r"jdbc:postgresql://([^:/]+):(\d+)/([^?]+)", jdbc_url)
    if not m:
        raise ValueError(f"DB_URL inattendue, impossible d'en extraire host/port/db : {jdbc_url}")
    return m.group(1), m.group(2), m.group(3)


def load_config(explicit_env_path: str | None = None) -> dict:
    env_file = find_env_file(explicit_env_path)
    raw = parse_env_file(env_file)

    required = ["DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "MINIO_URL", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_BUCKET",
                "STORAGE_ENCRYPTION_KEY"]
    missing = [k for k in required if k not in raw]
    if missing:
        raise ValueError(f"Clés manquantes dans {env_file} : {', '.join(missing)}")

    host, port, dbname = parse_jdbc_url(raw["DB_URL"])
    return {
        "env_file": str(env_file),
        "pg_host": host, "pg_port": port, "pg_db": dbname,
        "pg_user": raw["DB_USERNAME"], "pg_password": raw["DB_PASSWORD"],
        "minio_url": raw["MINIO_URL"],
        "minio_access_key": raw["MINIO_ACCESS_KEY"],
        "minio_secret_key": raw["MINIO_SECRET_KEY"],
        "minio_bucket": raw["MINIO_BUCKET"],
        "encryption_key": raw["STORAGE_ENCRYPTION_KEY"],
    }


# ─────────────────────────────────────────────────────────────────────────
# Base de données
# ─────────────────────────────────────────────────────────────────────────

def run_sql(cfg: dict, query: str) -> list[list[str]]:
    result = subprocess.run(
        ["psql", "-h", cfg["pg_host"], "-p", cfg["pg_port"], "-U", cfg["pg_user"],
         "-d", cfg["pg_db"], "-t", "-A", "-F", "\t", "-c", query],
        capture_output=True, text=True, check=False,
        # {**os.environ, ...} : on AJOUTE PGPASSWORD, on ne remplace pas tout
        # l'environnement — sinon PATH disparaît aussi et `psql` devient
        # introuvable (bug réel, attrapé en testant ce script).
        env={**os.environ, "PGPASSWORD": cfg["pg_password"]},
    )
    if result.returncode != 0:
        raise RuntimeError(f"Échec de la requête SQL :\n{result.stderr}")
    # splitlines() sur le texte BRUT, pas .strip() d'abord : .strip() mange
    # aussi les tabulations, donc une colonne finale vide (COALESCE(...,''))
    # sur la DERNIÈRE ligne perdrait sa colonne au split() — bug réel,
    # attrapé en testant ce script sur des documents sans projet.
    rows = []
    for line in result.stdout.splitlines():
        if line != "":
            rows.append(line.split("\t"))
    return rows


def escape_sql(value: str) -> str:
    return value.replace("'", "''")


def ids_sql_list(ids) -> str:
    """Valide que chaque id est bien numérique avant de l'interpoler dans du
    SQL — défense en profondeur, même si ces ids proviennent toujours d'une
    requête qu'on a faite nous-mêmes juste avant, jamais saisis à la main."""
    cleaned = []
    for i in ids:
        if not re.fullmatch(r"\d+", str(i)):
            raise ValueError(f"Id d'UO inattendu (non numérique) : {i!r}")
        cleaned.append(str(i))
    if not cleaned:
        raise ValueError("Aucun id fourni")
    return ", ".join(cleaned)


def fetch_uo_tree(cfg: dict) -> list[tuple[str, str, str | None]]:
    """(id, nom, parent_id) pour toutes les UO."""
    rows = run_sql(cfg, "SELECT id, nom, parent_id FROM unites_organisationnelles ORDER BY nom;")
    return [(r[0], r[1], r[2] if len(r) > 2 and r[2] else None) for r in rows]


def uo_tree_display_list(uos: list[tuple[str, str, str | None]]) -> list[str]:
    """Liste indentée façon arbre — pour l'affichage CLI (--list-uos)."""
    by_parent: dict[str | None, list[tuple[str, str]]] = {}
    for uo_id, nom, parent_id in uos:
        by_parent.setdefault(parent_id, []).append((uo_id, nom))

    lines: list[str] = []

    def walk(parent_id, depth):
        for uo_id, nom in sorted(by_parent.get(parent_id, []), key=lambda x: x[1]):
            lines.append(("  " * depth) + nom)
            walk(uo_id, depth + 1)

    walk(None, 0)
    return lines


def resolve_uo_ids_by_names(cfg: dict, noms: list[str]) -> dict[str, str]:
    """{nom -> id}, en signalant clairement un nom introuvable ou ambigu."""
    resultat = {}
    for nom in noms:
        rows = run_sql(cfg, f"SELECT id FROM unites_organisationnelles WHERE nom = '{escape_sql(nom)}';")
        if not rows:
            raise ValueError(f"Aucune UO nommée « {nom} »")
        if len(rows) > 1:
            raise ValueError(f"Plusieurs UO nommées « {nom} » — cas non géré, renommez-les")
        resultat[nom] = rows[0][0]
    return resultat


def resolve_subtree_ids(cfg: dict, uo_id: str) -> set[str]:
    """L'UO elle-même + toutes ses descendantes — utilisé par
    --include-children (CLI) et le bouton "Ajouter les descendants" (GUI),
    jamais imposé automatiquement : on choisit explicitement de l'appeler."""
    rows = run_sql(cfg, f"""
        WITH RECURSIVE sous_arbre AS (
            SELECT id FROM unites_organisationnelles WHERE id = {ids_sql_list([uo_id])}
            UNION ALL
            SELECT u.id FROM unites_organisationnelles u
            JOIN sous_arbre s ON u.parent_id = s.id
        )
        SELECT id FROM sous_arbre;
    """)
    return {r[0] for r in rows}


def fetch_documents_for_uo_ids(cfg: dict, uo_ids: set[str], exclude_corbeille: bool) -> list[dict]:
    """
    Documents appartenant EXACTEMENT aux UO données (aucune récursion
    cachée ici — l'appelant a déjà décidé du périmètre exact, UO par UO).
    Retourne id, titre, storage_key, uo_nom, projet_nom (vide si aucun).
    """
    statuts_exclus = ["DELETED"] + (["CORBEILLE"] if exclude_corbeille else [])
    statuts_sql = ", ".join(f"'{s}'" for s in statuts_exclus)

    query = f"""
        SELECT d.id, d.titre, d.storage_key, uo.nom, COALESCE(p.nom, '')
        FROM documents d
        JOIN unites_organisationnelles uo ON uo.id = d.uo_id
        LEFT JOIN projets p ON p.id = d.projet_id
        WHERE d.uo_id IN ({ids_sql_list(uo_ids)})
          AND d.status NOT IN ({statuts_sql})
        ORDER BY uo.nom, p.nom NULLS FIRST, d.create_at;
    """
    rows = run_sql(cfg, query)
    return [
        {"id": r[0], "titre": r[1], "storage_key": r[2], "uo_nom": r[3], "projet_nom": r[4]}
        for r in rows
    ]


# ─────────────────────────────────────────────────────────────────────────
# MinIO
# ─────────────────────────────────────────────────────────────────────────

MC_ALIAS = "uoexport"


def ensure_mc_alias(cfg: dict):
    subprocess.run(
        ["mc", "alias", "set", MC_ALIAS, cfg["minio_url"], cfg["minio_access_key"], cfg["minio_secret_key"]],
        capture_output=True, text=True, check=True,
    )


def fetch_encrypted_bytes(cfg: dict, storage_key: str) -> bytes:
    result = subprocess.run(
        ["mc", "cat", f"{MC_ALIAS}/{cfg['minio_bucket']}/{storage_key}"],
        capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout


# ─────────────────────────────────────────────────────────────────────────
# Déchiffrement — AES-256-GCM, même format que DocumentEncryptionService
# côté serveur (IV 12 octets + ciphertext+tag concaténés par Cipher.doFinal)
# ─────────────────────────────────────────────────────────────────────────

def decrypt(encrypted_bytes: bytes, key_b64: str) -> bytes:
    key = base64.b64decode(key_b64)
    if len(key) != 32:
        raise ValueError(f"STORAGE_ENCRYPTION_KEY invalide : {len(key)} octets décodés, 32 attendus")
    iv, ciphertext_and_tag = encrypted_bytes[:12], encrypted_bytes[12:]
    return AESGCM(key).decrypt(iv, ciphertext_and_tag, None)


def sanitize_filename(name: str, fallback: str) -> str:
    name = re.sub(r"[^\w\-. ]", "_", name or "").strip()
    return name[:120] if name else fallback


# ─────────────────────────────────────────────────────────────────────────
# Export — prend directement la liste FINALE de documents déjà choisis (pas
# de nouvelle requête ici : le choix UO et le choix document sont deux
# étapes distinctes, faites avant d'arriver ici)
# ─────────────────────────────────────────────────────────────────────────

def run_export(cfg: dict, documents: list[dict], separate_projects: bool, output_dir: Path,
                log=lambda msg: None, progress=lambda done, total: None) -> tuple[int, list]:
    total = len(documents)
    if not documents:
        log("Aucun document à exporter.")
        return 0, []

    ensure_mc_alias(cfg)
    output_dir.mkdir(parents=True, exist_ok=True)

    ok, failed = 0, []
    for i, doc in enumerate(documents, start=1):
        uo_folder = sanitize_filename(doc["uo_nom"], "UO")
        dest_dir = output_dir / uo_folder
        if separate_projects:
            projet_folder = sanitize_filename(doc["projet_nom"], "") or "Sans_projet"
            dest_dir = dest_dir / projet_folder
        dest_dir.mkdir(parents=True, exist_ok=True)

        dest = dest_dir / f"{doc['id']}_{sanitize_filename(doc['titre'], doc['id'])}.pdf"
        try:
            encrypted = fetch_encrypted_bytes(cfg, doc["storage_key"])
            plaintext = decrypt(encrypted, cfg["encryption_key"])
            dest.write_bytes(plaintext)
            ok += 1
            log(f"  ✓ {dest.relative_to(output_dir)}")
        except Exception as e:
            failed.append((doc["id"], doc["titre"], str(e)))
            log(f"  ✕ {doc['id']} ({doc['titre']}) : {e}")
        progress(i, total)

    log(f"\n{ok}/{total} document(s) exporté(s) dans {output_dir}/")
    if failed:
        log(f"{len(failed)} échec(s) — clé de chiffrement invalide ? objet absent de MinIO ?"
            f" contenu altéré (tag GCM invalide) ?")
    return ok, failed


# ─────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────

def run_cli(argv: list[str]):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--env-file", help="chemin explicite vers un .env (sinon auto-détecté)")
    parser.add_argument("--list-uos", action="store_true", help="lister les UO disponibles et quitter")
    parser.add_argument("--uo-noms", help="noms exacts des UO voulues, séparés par des virgules (ex: Ucad,Esp)")
    parser.add_argument("--include-children", action="store_true",
                         help="ajoute aussi toutes les descendantes de chaque UO listée dans --uo-noms")
    parser.add_argument("--list-documents", action="store_true",
                         help="lister les documents du périmètre choisi (id, UO, projet, titre) et quitter")
    parser.add_argument("--doc-ids", help="restreindre à ces ids de documents précis, séparés par des virgules "
                                           "(sinon : tous les documents du périmètre)")
    parser.add_argument("--separate-projects", action="store_true",
                         help="sous-dossiers séparés par projet, à l'intérieur de chaque dossier d'UO")
    parser.add_argument("--exclude-corbeille", action="store_true",
                         help="ne pas inclure les documents actuellement dans la corbeille")
    parser.add_argument("--output-dir", help="dossier de sortie (créé si absent)")
    args = parser.parse_args(argv)

    cfg = load_config(args.env_file)
    print(f"Configuration chargée depuis {cfg['env_file']}")

    if args.list_uos:
        uos = fetch_uo_tree(cfg)
        for line in uo_tree_display_list(uos):
            print(line)
        return

    if not args.uo_noms:
        parser.error("--uo-noms est requis (ou --list-uos seul)")

    noms = [n.strip() for n in args.uo_noms.split(",") if n.strip()]
    ids_par_nom = resolve_uo_ids_by_names(cfg, noms)
    uo_ids = set(ids_par_nom.values())
    if args.include_children:
        for uo_id in list(uo_ids):
            uo_ids |= resolve_subtree_ids(cfg, uo_id)

    documents = fetch_documents_for_uo_ids(cfg, uo_ids, args.exclude_corbeille)

    if args.list_documents:
        for d in documents:
            projet = d["projet_nom"] or "(sans projet)"
            print(f"{d['id']}\t{d['uo_nom']}\t{projet}\t{d['titre']}")
        return

    if args.doc_ids:
        voulus = {i.strip() for i in args.doc_ids.split(",") if i.strip()}
        documents = [d for d in documents if d["id"] in voulus]
        introuvables = voulus - {d["id"] for d in documents}
        if introuvables:
            print(f"Attention — id(s) non trouvé(s) dans le périmètre choisi : {', '.join(introuvables)}")

    if not args.output_dir:
        parser.error("--output-dir est requis pour exporter (utilisez --list-documents pour juste prévisualiser)")

    run_export(cfg, documents, args.separate_projects, Path(args.output_dir), log=print)


# ─────────────────────────────────────────────────────────────────────────
# GUI (Tkinter)
# ─────────────────────────────────────────────────────────────────────────

def run_gui():
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox

    root = tk.Tk()
    root.title("MadeArchive — Export de documents")
    root.geometry("760x680")

    state = {"cfg": None, "uos": [], "documents": []}

    # ── Ligne .env ──
    frm_env = ttk.Frame(root, padding=10)
    frm_env.pack(fill="x")
    env_var = tk.StringVar(value="(aucun .env chargé)")
    ttk.Label(frm_env, text="Fichier .env :").pack(side="left")
    ttk.Label(frm_env, textvariable=env_var, foreground="#555").pack(side="left", padx=6)

    def choisir_env():
        path = filedialog.askopenfilename(title="Choisir un fichier .env", filetypes=[(".env", "*.env"), ("Tous", "*")])
        if path:
            charger_config(path)

    ttk.Button(frm_env, text="Parcourir…", command=choisir_env).pack(side="right")

    # ── Étape 1 : choix des UO — sélection multiple exacte, pas
    #    "tout ou rien" : ctrl/cmd+clic ou glisser pour en choisir
    #    plusieurs précisément (ex: Ucad + Esp, sans MPI). ──────────────────
    frm_uo = ttk.Frame(root, padding=10)
    frm_uo.pack(fill="both", expand=False)
    ttk.Label(frm_uo, text="1. Unités organisationnelles (ctrl/cmd+clic pour en choisir plusieurs) :").pack(anchor="w")

    uo_tree = ttk.Treeview(frm_uo, show="tree", height=8, selectmode="extended")
    uo_tree.pack(fill="both", expand=True, pady=4)

    def selectionner_descendants():
        cfg = state["cfg"]
        if not cfg:
            return
        selection = list(uo_tree.selection())
        ajout = set(selection)
        for item_id in selection:
            ajout |= resolve_subtree_ids(cfg, item_id)
        uo_tree.selection_set(list(ajout))

    ttk.Button(frm_uo, text="+ Ajouter les descendantes de la sélection",
               command=selectionner_descendants).pack(anchor="w", pady=(0, 4))

    exclude_corbeille_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(frm_uo, text="Exclure les documents actuellement dans la corbeille",
                     variable=exclude_corbeille_var).pack(anchor="w")

    def charger_documents():
        cfg = state["cfg"]
        selection = uo_tree.selection()
        if not cfg or not selection:
            messagebox.showwarning("Sélection manquante", "Choisissez au moins une UO.")
            return
        try:
            documents = fetch_documents_for_uo_ids(cfg, set(selection), exclude_corbeille_var.get())
        except Exception as e:
            messagebox.showerror("Erreur", str(e))
            return
        state["documents"] = documents
        doc_tree.delete(*doc_tree.get_children())
        for d in documents:
            projet = d["projet_nom"] or "(sans projet)"
            doc_tree.insert("", "end", iid=d["id"], values=(d["uo_nom"], projet, d["titre"]))
        # Tout sélectionné par défaut — décocher un par un pour exclure,
        # plutôt que de devoir tout re-cocher pour un usage habituel.
        doc_tree.selection_set(doc_tree.get_children())
        log(f"{len(documents)} document(s) chargé(s) — tous sélectionnés par défaut, "
            f"ctrl/cmd+clic pour désélectionner ceux à exclure.")

    ttk.Button(frm_uo, text="→ Charger les documents de cette sélection",
               command=charger_documents).pack(anchor="w")

    # ── Étape 2 : choix des documents — sélection multiple exacte, un
    #    seul document possible sans prendre tout le groupe. ────────────────
    frm_doc = ttk.Frame(root, padding=10)
    frm_doc.pack(fill="both", expand=True)
    ttk.Label(frm_doc, text="2. Documents (ctrl/cmd+clic pour affiner la sélection) :").pack(anchor="w")

    doc_tree = ttk.Treeview(frm_doc, columns=("uo", "projet", "titre"), show="headings",
                             height=10, selectmode="extended")
    doc_tree.heading("uo", text="UO")
    doc_tree.heading("projet", text="Projet")
    doc_tree.heading("titre", text="Titre")
    doc_tree.column("uo", width=120)
    doc_tree.column("projet", width=140)
    doc_tree.column("titre", width=340)
    doc_tree.pack(fill="both", expand=True, pady=4)

    row_doc_btns = ttk.Frame(frm_doc)
    row_doc_btns.pack(fill="x")
    ttk.Button(row_doc_btns, text="Tout sélectionner",
               command=lambda: doc_tree.selection_set(doc_tree.get_children())).pack(side="left")
    ttk.Button(row_doc_btns, text="Tout désélectionner",
               command=lambda: doc_tree.selection_remove(doc_tree.get_children())).pack(side="left", padx=6)

    separate_projects_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(frm_doc, text="Séparer aussi par projet (sous-dossier dans chaque UO)",
                     variable=separate_projects_var).pack(anchor="w", pady=(6, 0))

    # ── Dossier de sortie ──
    frm_out = ttk.Frame(root, padding=10)
    frm_out.pack(fill="x")
    ttk.Label(frm_out, text="Dossier de sortie :").pack(anchor="w")
    out_var = tk.StringVar()
    row_out = ttk.Frame(frm_out)
    row_out.pack(fill="x", pady=4)
    ttk.Entry(row_out, textvariable=out_var).pack(side="left", fill="x", expand=True)

    def choisir_dossier():
        path = filedialog.askdirectory(title="Dossier de sortie")
        if path:
            out_var.set(path)

    ttk.Button(row_out, text="Choisir…", command=choisir_dossier).pack(side="left", padx=6)

    # ── Log + progression ──
    frm_log = ttk.Frame(root, padding=10)
    frm_log.pack(fill="both", expand=False)
    log_text = tk.Text(frm_log, height=8, state="disabled", wrap="word")
    log_text.pack(fill="both", expand=True)
    progress = ttk.Progressbar(root, mode="determinate")
    progress.pack(fill="x", padx=10, pady=(0, 10))

    def log(msg: str):
        def _append():
            log_text.configure(state="normal")
            log_text.insert("end", msg + "\n")
            log_text.see("end")
            log_text.configure(state="disabled")
        root.after(0, _append)

    def set_progress(done: int, total: int):
        root.after(0, lambda: progress.configure(maximum=max(total, 1), value=done))

    # ── Chargement config + arbre UO ──
    def charger_config(env_path=None):
        try:
            cfg = load_config(env_path)
            state["cfg"] = cfg
            env_var.set(cfg["env_file"])
            uos = fetch_uo_tree(cfg)
            state["uos"] = uos

            uo_tree.delete(*uo_tree.get_children())
            by_parent: dict[str | None, list[tuple[str, str]]] = {}
            for uo_id, nom, parent_id in uos:
                by_parent.setdefault(parent_id, []).append((uo_id, nom))

            def inserer(parent_item, parent_id):
                for uo_id, nom in sorted(by_parent.get(parent_id, []), key=lambda x: x[1]):
                    item = uo_tree.insert(parent_item, "end", iid=uo_id, text=nom, open=True)
                    inserer(item, uo_id)

            inserer("", None)
            log(f"UO chargées depuis {cfg['env_file']} — {len(uos)} UO trouvées.")
        except Exception as e:
            messagebox.showerror("Erreur de configuration", str(e))

    # ── Lancement (thread séparé pour ne pas geler la fenêtre) ──
    def lancer():
        cfg = state["cfg"]
        if not cfg:
            messagebox.showwarning("Configuration manquante", "Aucun .env chargé.")
            return
        selection_docs = doc_tree.selection()
        if not selection_docs:
            messagebox.showwarning("Sélection manquante",
                                    "Chargez des documents (étape 1) puis sélectionnez-en au moins un (étape 2).")
            return
        if not out_var.get():
            messagebox.showwarning("Dossier manquant", "Choisissez un dossier de sortie.")
            return

        documents_par_id = {d["id"]: d for d in state["documents"]}
        documents_choisis = [documents_par_id[i] for i in selection_docs if i in documents_par_id]

        btn_lancer.configure(state="disabled")
        progress.configure(value=0)

        def worker():
            try:
                run_export(cfg, documents_choisis, separate_projects_var.get(),
                           Path(out_var.get()), log=log, progress=set_progress)
            except Exception as e:
                log(f"Erreur : {e}")
            finally:
                root.after(0, lambda: btn_lancer.configure(state="normal"))

        threading.Thread(target=worker, daemon=True).start()

    btn_lancer = ttk.Button(root, text="Exporter la sélection", command=lancer)
    btn_lancer.pack(pady=(0, 10))

    charger_config()  # auto-détection au démarrage
    root.mainloop()


if __name__ == "__main__":
    if len(sys.argv) > 1:
        run_cli(sys.argv[1:])
    else:
        run_gui()
