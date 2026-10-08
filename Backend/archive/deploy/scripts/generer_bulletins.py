#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Génère un grand jeu de relevés de notes FICTIFS (PDF) pour tester MadeArchive en charge
(import en masse, OCR, indexation Meilisearch, recherche, intégrité...).

Modèle : les relevés de notes de l'École Supérieure Polytechnique (UCAD) — un PDF par étudiant,
par semestre et par session (normale ou rattrapage), avec les UE, les éléments constitutifs (EC),
coefficients, moyennes, crédits, rang, décision du conseil.

Arborescence produite (un dossier = une classe d'une formation d'une année académique) :

    <sortie>/<DEPARTEMENT>/<ANNEE>/<FORMATION>/<CLASSE>-<PARCOURS>/<relevé>.pdf
    ex. DGI/2024-2025/GLSI/M1-Jour/DGI_GLSI_M1-Jour_2024-2025_S2_RATTRAPAGE_24-GLSI-M1J-0037_DIOP-Fatou.pdf

Chaque nom de fichier est unique ET cohérent avec le contenu : département, formation, classe,
année, numéro de semestre PROPRE À LA CLASSE (L1 : S1-S2, L2 : S3-S4, L3 : S5-S6 ; DUT1 : S1-S2,
DUT2 : S3-S4 ; DIC1 : S5-S6, DIC2 : S7-S8, DIC3 : S9-S10 ; M1 : S1-S2, M2 : S3-S4), session
(NORMALE / RATTRAPAGE) et matricule de l'étudiant. Un relevé de rattrapage n'existe que pour un
étudiant qui a réellement échoué à au moins une UE (les notes sont calculées, pas tirées au hasard
indépendamment : EC → UE → semestre → crédits → décision).

Tout est FICTIF : noms, dates de naissance, notes. Aucun cachet, aucune signature, aucun nom de
responsable réel ; chaque page porte la mention « document fictif ». Un fichier manifeste.csv liste
les métadonnées de chaque PDF (utile pour contrôler l'import et les recherches).

Dépendance : reportlab  (pip install reportlab).  Déterministe : même --graine = mêmes fichiers.
Reprenable : relancer la commande saute les classes déjà terminées.

Exemples :
    python3 generer_bulletins.py --dry-run                      # compte exact, rien n'est écrit
    python3 generer_bulletins.py --limite-classes 3 --sortie /tmp/essai
    python3 generer_bulletins.py                                # ~1,05 million de PDF
    python3 generer_bulletins.py --cible 2000000 --annees 25
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import math
import os
import random
import sys
import time
import unicodedata
import zlib
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

# ════════════════════════════════════════════════════════════════════════════
# 1. Référentiel : départements, formations, classes
# ════════════════════════════════════════════════════════════════════════════

# formation : (intitulé, cycles proposés, parcours du soir en plus du jour)
DEPARTEMENTS = {
    "DGI": ("GÉNIE INFORMATIQUE", {
        "GLSI": ("Génie Logiciel et Système d'Information", ("LICENCE", "MASTER"), True),
        "SRT":  ("Systèmes, Réseaux et Télécommunications", ("LICENCE", "MASTER"), True),
        "IABD": ("Intelligence Artificielle et Big Data", ("MASTER",), False),
        "CYB":  ("Cybersécurité et Sécurité des Réseaux", ("DIC",), False),
        "INFO": ("Informatique Appliquée", ("DUT", "DIC"), False),
    }),
    "DGE": ("GÉNIE ÉLECTRIQUE", {
        "ELT": ("Électrotechnique", ("DUT", "DIC"), False),
        "ELN": ("Électronique et Systèmes Embarqués", ("LICENCE", "MASTER"), False),
        "AII": ("Automatique et Informatique Industrielle", ("DUT", "DIC"), False),
        "ENR": ("Énergies Renouvelables et Efficacité Énergétique", ("MASTER",), False),
        "ELI": ("Électricité Industrielle", ("LICENCE",), False),
    }),
    "DGC": ("GÉNIE CIVIL", {
        "BAT":  ("Bâtiment et Construction", ("DUT", "DIC"), False),
        "TP":   ("Travaux Publics", ("DUT", "DIC"), False),
        "HYD":  ("Hydraulique et Assainissement", ("LICENCE", "MASTER"), False),
        "GEO":  ("Géotechnique et Fondations", ("MASTER",), False),
        "TOPO": ("Topographie et Géomatique", ("LICENCE",), False),
    }),
    "DGM": ("GÉNIE MÉCANIQUE", {
        "FAB": ("Fabrication Mécanique", ("DUT", "DIC"), False),
        "MAI": ("Maintenance Industrielle", ("LICENCE", "MASTER"), False),
        "FRO": ("Froid et Climatisation", ("LICENCE",), False),
        "ENE": ("Énergétique et Mécatronique", ("MASTER",), False),
        "CMC": ("Conception Mécanique", ("DIC",), False),
    }),
    "GCBA": ("GÉNIE CHIMIQUE ET BIOLOGIE APPLIQUÉE", {
        "GCH": ("Génie Chimique et Procédés", ("DUT", "DIC"), False),
        "BIO": ("Biotechnologies", ("LICENCE", "MASTER"), False),
        "AGR": ("Industries Agroalimentaires", ("DUT", "DIC"), False),
        "EHS": ("Eau, Environnement et HSE", ("MASTER",), False),
    }),
    "DGEST": ("GESTION", {
        "CG":  ("Comptabilité, Contrôle et Audit", ("LICENCE", "MASTER"), True),
        "MKT": ("Marketing et Communication Digitale", ("LICENCE", "MASTER"), True),
        "LOG": ("Logistique et Transport", ("LICENCE", "MASTER"), True),
        "FIN": ("Banque, Finance et Assurance", ("LICENCE", "MASTER"), True),
        "RH":  ("Gestion des Ressources Humaines", ("LICENCE",), True),
    }),
}

# cycle : (libellé du diplôme, [(classe, niveau, premier semestre de la classe, poids d'effectif)])
CYCLES = {
    "LICENCE": ("LICENCE EN", [("L1", 1, 1, 1.5), ("L2", 2, 3, 1.2), ("L3", 3, 5, 1.0)]),
    "DUT":     ("DIPLÔME UNIVERSITAIRE DE TECHNOLOGIE EN", [("DUT1", 1, 1, 1.3), ("DUT2", 2, 3, 1.0)]),
    "DIC":     ("DIPLÔME D'INGÉNIEUR DE CONCEPTION EN", [("DIC1", 3, 5, 0.9), ("DIC2", 4, 7, 0.8), ("DIC3", 5, 9, 0.7)]),
    "MASTER":  ("MASTER EN", [("M1", 4, 1, 0.6), ("M2", 5, 3, 0.5)]),
}
NOM_CYCLE = {"LICENCE": "Licence", "DUT": "DUT", "DIC": "DIC", "MASTER": "Master"}
CLASSE_FINALE = {"L3", "DUT2", "DIC3", "M2"}                  # la décision de S2 y est « diplômé »
ORDINAL = {1: "PREMIÈRE", 2: "DEUXIÈME", 3: "TROISIÈME"}

# ── Catalogues de cours (réalistes mais fictifs), par département ──────────────
EC = {
    "DGI": ["Algorithmique et structures de données", "Programmation orientée objet", "Bases de données relationnelles",
            "Systèmes d'exploitation", "Réseaux informatiques", "Génie logiciel", "Architecture des ordinateurs",
            "Développement web", "Programmation mobile", "Analyse et conception UML", "Sécurité informatique",
            "Administration Linux", "Compilation", "Théorie des graphes", "Intelligence artificielle",
            "Apprentissage automatique", "Fouille de données", "Cloud computing", "Virtualisation et conteneurs",
            "Méthodes agiles", "Tests et qualité logicielle", "Systèmes répartis", "Interface homme-machine",
            "Services réseaux", "Programmation système", "Traitement d'images", "Recherche opérationnelle",
            "Calcul scientifique", "Big data", "Internet des objets", "Cryptographie", "Gestion de projet",
            "Systèmes embarqués", "Télécommunications numériques", "Protocoles de routage", "Supervision réseau",
            "Ingénierie des exigences", "Entrepôts de données", "Optimisation", "Analyse numérique"],
    "DGE": ["Circuits électriques", "Électronique analogique", "Électronique numérique", "Électrotechnique générale",
            "Machines électriques", "Électronique de puissance", "Automatique linéaire", "Asservissements",
            "Microcontrôleurs", "Traitement du signal", "Réseaux électriques", "Distribution d'énergie électrique",
            "Énergies renouvelables", "Systèmes photovoltaïques", "Instrumentation et mesures", "Automates programmables",
            "Régulation industrielle", "Haute tension", "Protection des réseaux", "Systèmes embarqués",
            "Capteurs et actionneurs", "Électromagnétisme", "Physique des semi-conducteurs", "Conversion d'énergie",
            "Électrification rurale", "Supervision industrielle", "Robotique industrielle", "Maintenance électrique",
            "Schémas et câblage", "Qualité de l'énergie", "Éolien et hydroélectricité", "Transformateurs",
            "Commande des machines", "Logique programmée", "Domotique et bâtiment intelligent",
            "Sûreté de fonctionnement", "Normes et sécurité électrique", "Dessin assisté par ordinateur",
            "Analyse numérique", "Mathématiques pour l'ingénieur"],
    "DGC": ["Résistance des matériaux", "Mécanique des sols", "Béton armé", "Structures métalliques", "Topographie",
            "Hydraulique générale", "Hydrologie", "Dessin assisté par ordinateur", "Matériaux de construction",
            "Routes et terrassements", "Ouvrages d'art", "Fondations", "Calcul des structures", "Éléments finis",
            "Géotechnique", "Assainissement", "Alimentation en eau potable", "Béton précontraint",
            "Organisation de chantier", "Métré et estimation", "Droit de la construction", "BIM et maquette numérique",
            "Thermique du bâtiment", "Urbanisme et aménagement", "Géologie appliquée", "Charpente bois",
            "Mécanique des fluides", "Travaux maritimes", "Génie parasismique", "Pathologie des ouvrages",
            "Sécurité sur les chantiers", "Management de projets de construction", "Cartographie et SIG",
            "Voiries et réseaux divers", "Dimensionnement des ouvrages", "Béton et durabilité", "Hydraulique urbaine",
            "Analyse numérique", "Statistiques appliquées", "Mécanique des structures"],
    "DGM": ["Mécanique des solides", "Thermodynamique", "Mécanique des fluides", "Conception mécanique (CAO)",
            "Fabrication mécanique", "Résistance des matériaux", "Métrologie dimensionnelle", "Transferts thermiques",
            "Machines thermiques", "Hydraulique et pneumatique", "Matériaux métalliques", "Soudage et assemblage",
            "Usinage à commande numérique", "Maintenance industrielle", "Gestion de la maintenance (GMAO)",
            "Vibrations mécaniques", "Automatique pour mécaniciens", "Froid et climatisation", "Moteurs thermiques",
            "Turbomachines", "Dessin technique", "Éléments de machines", "Plasturgie", "Fonderie", "Robotique",
            "Fiabilité et sûreté", "Énergétique", "Calcul de structures", "Gestion de production", "Lean manufacturing",
            "Qualité et normes ISO 9001", "Contrôle non destructif", "Tribologie", "Mécatronique",
            "Simulation numérique", "Chaudronnerie", "Mécanique analytique", "Tolérancement",
            "Engrenages et transmissions", "Fabrication additive"],
    "GCBA": ["Chimie générale", "Chimie organique", "Chimie analytique", "Thermodynamique chimique", "Opérations unitaires",
             "Génie des procédés", "Cinétique chimique", "Biochimie", "Microbiologie", "Biologie cellulaire",
             "Génie de la réaction chimique", "Transfert de matière", "Analyse instrumentale", "Procédés de séparation",
             "Contrôle qualité", "Hygiène et sécurité (HSE)", "Traitement des eaux", "Traitement des effluents",
             "Bioprocédés", "Biotechnologies", "Technologie alimentaire", "Conservation des aliments",
             "Chimie des matériaux", "Corrosion", "Pétrochimie", "Raffinage", "Polymères",
             "Environnement et développement durable", "Plans d'expériences", "Régulation de procédés",
             "Dimensionnement d'équipements", "Valorisation de la biomasse", "Toxicologie", "Immunologie",
             "Génétique", "Biostatistiques", "Phytochimie", "Industries agroalimentaires", "Management de la qualité",
             "Analyse sensorielle"],
    "DGEST": ["Comptabilité générale", "Comptabilité analytique", "Analyse financière", "Droit commercial",
              "Droit du travail", "Marketing fondamental", "Marketing digital", "Gestion des ressources humaines",
              "Management des organisations", "Statistiques appliquées à la gestion", "Mathématiques financières",
              "Microéconomie", "Macroéconomie", "Gestion de la production", "Logistique et transport",
              "Gestion des stocks", "Supply chain management", "Fiscalité des entreprises", "Contrôle de gestion",
              "Audit et contrôle interne", "Gestion budgétaire", "Techniques bancaires", "Gestion de portefeuille",
              "Entrepreneuriat", "Étude de marché", "Négociation commerciale", "Systèmes d'information de gestion",
              "Gestion de projet", "Commerce international", "Douane et transit", "Économie du développement",
              "Finance d'entreprise", "Gestion de la trésorerie", "Assurances", "Comptabilité des sociétés",
              "Communication d'entreprise", "Informatique de gestion", "Recherche opérationnelle",
              "Gestion de la qualité", "Économétrie"],
}
UE = {
    "DGI": ["Approfondissement en algorithmique et langages", "Systèmes et Réseaux Informatiques",
            "Fondamentaux en Informatique", "Fondamentaux des Technologies Émergentes", "Outils et méthodes mathématiques",
            "Ingénierie logicielle avancée", "Systèmes et langages avancés", "Données et connaissances",
            "Systèmes et réseaux avancés", "Ingénierie et gestion de projets informatiques", "Sécurité et cryptographie",
            "Développement d'applications"],
    "DGE": ["Fondamentaux de l'électricité", "Électronique et systèmes numériques", "Énergie et machines électriques",
            "Automatique et régulation", "Réseaux et distribution d'énergie", "Instrumentation et mesures",
            "Électronique de puissance et commande", "Systèmes embarqués et programmation", "Énergies renouvelables",
            "Maintenance et sûreté de fonctionnement", "Outils mathématiques et physiques", "Informatique industrielle"],
    "DGC": ["Mécanique des structures", "Matériaux et construction", "Géotechnique et fondations",
            "Hydraulique et hydrologie", "Topographie et SIG", "Routes et ouvrages d'art", "Béton armé et précontraint",
            "Organisation et gestion de chantier", "Conception assistée par ordinateur", "Bâtiment et confort thermique",
            "Outils mathématiques et numériques", "Environnement et assainissement"],
    "DGM": ["Mécanique générale", "Conception et fabrication", "Énergétique et thermique", "Matériaux et procédés",
            "Mécanique des fluides et machines", "Automatisation et mécatronique", "Maintenance et fiabilité",
            "Production industrielle", "Métrologie et qualité", "Froid et climatisation",
            "Outils mathématiques et numériques", "Simulation et calcul de structures"],
    "GCBA": ["Chimie fondamentale", "Génie des procédés", "Biologie et biochimie", "Analyse et contrôle qualité",
             "Opérations unitaires", "Biotechnologies et bioprocédés", "Environnement et traitement des eaux",
             "Technologie alimentaire", "Matériaux et polymères", "Sécurité et qualité (HSE)",
             "Outils mathématiques et statistiques", "Cinétique et réacteurs"],
    "DGEST": ["Comptabilité et finance", "Droit et fiscalité", "Marketing et commercial", "Management et ressources humaines",
              "Gestion de la production et logistique", "Contrôle de gestion et audit", "Économie et statistiques",
              "Outils quantitatifs de gestion", "Systèmes d'information de gestion", "Entrepreneuriat et projets",
              "Finance et banque", "Commerce international"],
}

PRENOMS_F = ["Fatou", "Aminata", "Awa", "Khady", "Mariama", "Aïssatou", "Ndeye", "Coumba", "Rokhaya", "Sokhna", "Astou",
             "Bineta", "Dieynaba", "Marième", "Safiatou", "Fatoumata", "Adja", "Maimouna", "Ramatoulaye", "Nafissatou",
             "Mame Diarra", "Seynabou", "Yacine", "Oumou", "Penda", "Salimata", "Aïda", "Chantal", "Grâce", "Esther",
             "Sarah", "Marie", "Julie", "Nadège", "Léa", "Binta", "Fanta", "Kadiatou", "Hawa", "Ndèye Fatou"]
PRENOMS_M = ["Mamadou", "Moussa", "Ousmane", "Ibrahima", "Abdoulaye", "Cheikh", "Modou", "Papa", "Babacar", "Serigne",
             "Mouhamed", "Alioune", "Samba", "Pape", "Souleymane", "Boubacar", "Omar", "Lamine", "Demba", "Malick",
             "Idrissa", "Mbaye", "Assane", "Amadou", "Daouda", "Landing", "Thierno", "Bachir", "El Hadji", "Gora",
             "Jean", "Pierre", "Joseph", "Michel", "Emmanuel", "Daniel", "David", "Samuel", "Paul", "Abdou"]
NOMS = ["DIOP", "NDIAYE", "FALL", "SOW", "BA", "DIALLO", "SECK", "GUEYE", "SARR", "CISSE", "THIAM", "FAYE", "MBAYE",
        "NDOUR", "KANE", "SY", "TOURE", "SAMB", "DIOUF", "BADJI", "SENE", "MBOW", "LO", "DIENG", "NIANG", "WADE",
        "CAMARA", "TRAORE", "KEITA", "COULIBALY", "SANE", "MANGA", "GOMIS", "MENDY", "DIATTA", "BASSENE", "TINE",
        "NGOM", "KA", "LY", "SAGNA", "DRAME", "KONATE", "SYLLA", "BARRY", "BALDE", "DJIBA", "DIAGNE", "GAYE", "SALL",
        "MBODJ", "NDAO", "DABO", "FOFANA", "SOUMARE", "DIABY", "TANDIAN", "LAMINE", "KONE", "BADIANE", "NDIONE"]
LIEUX = ["Dakar", "Thiès", "Saint-Louis", "Kaolack", "Ziguinchor", "Touba", "Rufisque", "Mbour", "Diourbel", "Louga",
         "Tambacounda", "Kolda", "Fatick", "Pikine", "Guédiawaye", "Bamako", "Abidjan", "Conakry", "Nouakchott",
         "Bangui", "Libreville", "Cotonou", "Lomé", "Brazzaville", "Ouagadougou", "Niamey"]

# Paramètres de simulation des notes (calés pour ~30 % des semestres avec au moins une UE à rattraper)
MU, SD_ETUDIANT, SD_UE, SD_EC = 13.8, 1.8, 1.1, 2.8
MARGE_CIBLE = 1.005


# ════════════════════════════════════════════════════════════════════════════
# 2. Cohortes (une classe d'une formation pour une année académique) et maquettes
# ════════════════════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class Cohorte:
    dept: str
    annee: int            # année de début : 2025 pour 2025-2026
    form: str
    cycle: str
    classe: str           # L1, M2, DIC3...
    niveau: int
    sem0: int             # numéro du premier semestre de la classe
    ordinal: int          # 1re, 2e, 3e année du cycle
    parcours: str         # Jour / Soir
    poids: float

    @property
    def cle(self) -> str:
        return f"{self.dept}_{self.annee}-{self.annee + 1}_{self.form}_{self.classe}-{self.parcours}"

    @property
    def annee_ac(self) -> str:
        return f"{self.annee}-{self.annee + 1}"

    @property
    def dossier(self) -> str:
        return f"{self.dept}/{self.annee_ac}/{self.form}/{self.classe}-{self.parcours}"

    @property
    def label_classe(self) -> str:
        return f"{NOM_CYCLE[self.cycle]}-{self.form}-{self.ordinal}-{self.parcours}"


def lister_cohortes(annees: list[int], departements: list[str]) -> list[Cohorte]:
    out = []
    for annee in annees:
        for dept in departements:
            for form, (_nom, cycles, soir) in DEPARTEMENTS[dept][1].items():
                for cycle in cycles:
                    for i, (classe, niveau, sem0, poids) in enumerate(CYCLES[cycle][1]):
                        for parcours in (("Jour", "Soir") if soir and cycle in ("LICENCE", "MASTER") else ("Jour",)):
                            out.append(Cohorte(dept, annee, form, cycle, classe, niveau, sem0, i + 1, parcours,
                                               poids * (0.6 if parcours == "Soir" else 1.0)))
    return out


def graine_de(texte: str, graine: int) -> random.Random:
    return random.Random(zlib.crc32(texte.encode("utf-8")) ^ (graine * 2654435761 & 0xFFFFFFFF))


@lru_cache(maxsize=None)
def maquette(dept: str, form: str, cycle: str, classe: str, niveau: int, sem_idx: int):
    """UE du semestre (sem_idx = 1 ou 2 dans l'année) : même maquette chaque année pour une classe donnée."""
    rng = random.Random(zlib.crc32(f"maquette|{dept}|{form}|{classe}|{sem_idx}".encode()))
    ecs, ues = EC[dept][:], UE[dept][:]
    rng.shuffle(ecs)
    rng.shuffle(ues)
    prefixe = f"{cycle}-{form}-{niveau}{sem_idx}"
    credits = [5, 5, 5, 5, 5, 5] if sem_idx == 1 else [6, 5, 5, 5, 5, 4]
    coef = 3.0 if sem_idx == 1 else 1.0
    res, pos = [], 0
    for u in range(5):
        liste = []
        for e in range(rng.choice((2, 3, 3))):
            liste.append((f"{prefixe}{u + 1}{e + 1}", ecs[pos % len(ecs)], coef))
            pos += 1
        res.append((f"{prefixe}{u + 1}", ues[u], credits[u], tuple(liste), False))
    if sem_idx == 1:
        res.append((f"{prefixe}6", "Communication et Droit (obligatoire)", credits[5], (
            (f"{prefixe}61", "TEC (Communication d'entreprise ou Communication interne et externe)", 2.0),
            (f"{prefixe}62", "Droit de l'entreprise et du travail", 2.0),
            (f"{prefixe}63", "Anglais", 2.0)), True))
    else:
        res.append((f"{prefixe}6", "Projet transversal", credits[5], (
            (f"{prefixe}61", "Rapport Technique", 1.0),
            (f"{prefixe}62", "Présentation Orale", 1.0)), True))
    return tuple(res)


# ════════════════════════════════════════════════════════════════════════════
# 3. Simulation : EC → UE → semestre → crédits → décision, session normale puis rattrapage
# ════════════════════════════════════════════════════════════════════════════

def borne(x: float) -> float:
    return round(min(20.0, max(0.0, x)), 2)


def moy_ponderee(valeurs, poids) -> float:
    return round(sum(v * p for v, p in zip(valeurs, poids)) / sum(poids), 2)


def effectif_de(c: Cohorte, base: float, rng: random.Random) -> int:
    return max(8, round(base * c.poids * rng.uniform(0.85, 1.15)))


def date_edition(annee: int, mois: int, jour: int, rng: random.Random) -> dt.date:
    d = dt.date(annee + 1, mois, jour) + dt.timedelta(days=rng.randint(-3, 3))
    return min(d, dt.date.today())


def simuler_cohorte(c: Cohorte, base: float, graine: int) -> list[dict]:
    """Tous les relevés de la classe : un dict par PDF (normale S1, normale S2, rattrapages éventuels)."""
    rng = graine_de(c.cle, graine)
    n = effectif_de(c, base, rng)
    maq = [maquette(c.dept, c.form, c.cycle, c.classe, c.niveau, s) for s in (1, 2)]
    aa = f"{c.annee % 100:02d}"
    J = c.parcours[0]
    age = {"L1": 19, "L2": 20, "L3": 21, "DUT1": 19, "DUT2": 20, "DIC1": 21, "DIC2": 22, "DIC3": 23,
           "M1": 23, "M2": 24}[c.classe]

    etus = []
    for i in range(n):
        feminin = rng.random() < 0.38
        pool = PRENOMS_F if feminin else PRENOMS_M
        prenoms = rng.choice(pool) + (" " + rng.choice(pool) if rng.random() < 0.35 else "")
        naiss = dt.date(c.annee - age - rng.randint(-1, 2), rng.randint(1, 12), rng.randint(1, 28))
        etu = {
            "matricule": f"{aa}-{c.form}-{c.classe}{J}-{i + 1:04d}",
            "nom": rng.choice(NOMS), "prenoms": prenoms,
            "naissance": naiss, "lieu": rng.choice(LIEUX),
            "ability": rng.gauss(MU, SD_ETUDIANT),
        }
        a = etu["ability"]
        etu["sem"] = []
        for s in (0, 1):
            ues = []
            for (code, titre, credits, ecs, transv) in maq[s]:
                eff = rng.gauss(0, SD_UE) + (1.2 if transv and s == 1 else 0.0)
                marks = [borne(a + eff + rng.gauss(0, SD_EC)) for _ in ecs]
                moy = moy_ponderee(marks, [e[2] for e in ecs])
                ues.append({"marks": marks, "moy": moy, "ses": "NORMALE", "abs": rng.choice((0, 0, 0, 1, 1, 2, 3, 5, 6))})
            etu["sem"].append(ues)
        etus.append(etu)

    # statistiques de classe (session normale) et rang, par semestre
    stats = []
    for s in (0, 1):
        moys_ue = [[e["sem"][s][u]["moy"] for e in etus] for u in range(6)]
        moy_sem = [moy_ponderee([ue["moy"] for ue in e["sem"][s]], [m[2] for m in maq[s]]) for e in etus]
        ordre = sorted(range(n), key=lambda k: -moy_sem[k])
        rang = [0] * n
        for r, k in enumerate(ordre):
            rang[k] = r + 1
        stats.append({
            "ue_moy": [round(sum(m) / n, 2) for m in moys_ue], "ue_max": [max(m) for m in moys_ue],
            "classe": round(sum(moy_sem) / n, 2), "rang": rang,
        })

    bulletins = []
    d_s1, d_s2, d_rat = (date_edition(c.annee, 4, 30, rng), date_edition(c.annee, 7, 26, rng),
                         date_edition(c.annee, 11, 3, rng))
    for k, e in enumerate(etus):
        a = e["ability"]
        normale = []                                                       # résultat de chaque semestre, session normale
        for s in (0, 1):
            ues = e["sem"][s]
            credits_ues = [m[2] for m in maq[s]]
            normale.append(_resultat(ues, credits_ues))
        # rattrapage : on refait les EC < 10 des UE non validées
        rattrapees = []
        for s in (0, 1):
            ues_r = []
            for u, ue in enumerate(e["sem"][s]):
                if ue["moy"] >= 10:
                    ues_r.append(ue)
                    continue
                marks = [m if m >= 10 else borne(max(m, rng.gauss(a - 0.7, 2.6))) for m in ue["marks"]]
                ues_r.append({"marks": marks, "moy": moy_ponderee(marks, [x[2] for x in maq[s][u][3]]),
                              "ses": "RATTRAPAGE", "abs": ue["abs"]})
            rattrapees.append(ues_r)
        apres = [_resultat(rattrapees[s], [m[2] for m in maq[s]]) for s in (0, 1)]
        rat = [normale[s]["credits"] < 30 for s in (0, 1)]

        def bull(s, session, ues, res, date):
            b = {"cohorte": c, "etu": e, "sem_idx": s + 1, "sem_num": c.sem0 + s, "session": session,
                 "ues": ues, "maquette": maq[s], "res": res, "date": date,
                 "stats": {"ue_moy": stats[s]["ue_moy"], "ue_max": stats[s]["ue_max"],
                           "classe": stats[s]["classe"], "rang": stats[s]["rang"][k], "effectif": n}}
            return b

        # S1 normale, S2 normale (bilan annuel : S1 telle qu'elle était à la date de ce relevé)
        b1 = bull(0, "NORMALE", e["sem"][0], normale[0], d_s1)
        b2 = bull(1, "NORMALE", e["sem"][1], normale[1], d_s2)
        b2["annuel"] = _annuel(c, normale[0], normale[1], definitif=False)
        bulletins += [b1, b2]
        if rat[0]:
            bulletins.append(bull(0, "RATTRAPAGE", rattrapees[0], apres[0], d_rat))
        if rat[0] or rat[1]:
            b = bull(1, "RATTRAPAGE", rattrapees[1], apres[1], d_rat)
            b["annuel"] = _annuel(c, apres[0], apres[1], definitif=True)
            bulletins.append(b)
    return bulletins


def _resultat(ues, credits_ues) -> dict:
    credits = sum(cr for ue, cr in zip(ues, credits_ues) if ue["moy"] >= 10)
    return {"moy": moy_ponderee([ue["moy"] for ue in ues], credits_ues), "credits": float(credits),
            "valide": credits == 30}


def _annuel(c: Cohorte, r1: dict, r2: dict, definitif: bool) -> dict:
    total = r1["credits"] + r2["credits"]
    moy = round((r1["moy"] + r2["moy"]) / 2, 2)
    if total == 60:
        decision = "Admis(e) au diplôme" if c.classe in CLASSE_FINALE else "Passe en classe supérieure"
    elif not definitif:
        decision = "Autorisé(e) à faire la session de rattrapage"
    else:
        decision = "Exclu(e) de la formation" if moy < 7 else "Redouble la classe"
    return {"total": total, "moy": moy, "decision": decision}


# ════════════════════════════════════════════════════════════════════════════
# 4. Rendu PDF (reportlab) — mise en page inspirée du relevé réel, sans cachet ni signature
# ════════════════════════════════════════════════════════════════════════════

def fmt(x: float) -> str:
    s = f"{x:.2f}".rstrip("0")
    return s + "0" if s.endswith(".") else s


def ascii_nom(texte: str) -> str:
    s = unicodedata.normalize("NFKD", texte).encode("ascii", "ignore").decode()
    return "-".join(s.replace("'", " ").split())


def nom_fichier(b: dict) -> str:
    c, e = b["cohorte"], b["etu"]
    return (f"{c.dept}_{c.form}_{c.classe}-{c.parcours}_{c.annee_ac}_S{b['sem_num']}_{b['session']}_"
            f"{e['matricule']}_{ascii_nom(e['nom'])}-{ascii_nom(e['prenoms'].split()[0])}.pdf")


def rendre(b: dict, chemin: Path) -> None:
    from reportlab.pdfgen import canvas
    c, e, res = b["cohorte"], b["etu"], b["res"]
    X0, X1 = 34, 561
    cv = canvas.Canvas(str(chemin), pagesize=(595.27, 841.89), pageCompression=1)
    cv.setTitle(f"Relevé de notes S{b['sem_num']} {b['session']} — {e['prenoms']} {e['nom']} (fictif)")
    cv.setAuthor("Jeu de test MadeArchive")
    cv.setLineWidth(0.7)
    centre = (X0 + X1) / 2

    def box(x, y, w, h):
        cv.rect(x, y, w, h)

    def txt(x, y, s, font="Helvetica", size=8, align="l"):
        cv.setFont(font, size)
        {"l": cv.drawString, "c": cv.drawCentredString, "r": cv.drawRightString}[align](x, y, s)

    nom_dept, formations = DEPARTEMENTS[c.dept]
    intitule = formations[c.form][0]
    libelle_cycle = CYCLES[c.cycle][0]

    # En-tête
    box(X0, 752, X1 - X0, 56)
    txt(centre, 794, "UNIVERSITÉ CHEIKH ANTA DIOP DE DAKAR", "Helvetica", 10, "c")
    txt(centre, 782, "ÉCOLE SUPÉRIEURE POLYTECHNIQUE", "Helvetica", 9, "c")
    txt(centre, 770, f"DÉPARTEMENT {nom_dept}", "Helvetica", 9, "c")
    txt(X1 - 4, 757, f"Année Universitaire : {c.annee_ac}", "Helvetica", 8, "r")
    # Titre de la formation
    box(X0, 716, X1 - X0, 30)
    txt(centre, 733, f"{libelle_cycle} {intitule.upper()} ({NOM_CYCLE[c.cycle].upper()}-{c.form})", "Helvetica-Bold", 9.5, "c")
    txt(centre, 721, f"Option: {intitule}", "Helvetica-Bold", 9, "c")
    # Classe, semestre, session
    txt(centre, 705, f"{ORDINAL[c.ordinal]} ANNÉE ( {c.classe} )", "Helvetica-Bold", 8.5, "c")
    txt(centre, 695, f"Classe : {c.label_classe}", "Helvetica", 8.5, "c")
    txt(centre, 684, f"RELEVÉ DE NOTES DU SEMESTRE {b['sem_num']}", "Helvetica-Bold", 9.5, "c")
    txt(X1, 688, f"Session: {b['session']}", "Helvetica-Bold", 7, "r")
    # Étudiant
    box(X0, 648, X1 - X0, 30)
    txt(X0 + 3, 668, f"Prénom(s) et Nom : {e['prenoms'].upper()} {e['nom']}", "Helvetica-Bold", 9)
    txt(X0 + 3, 656, f"Date et lieu de naissance : {e['naissance']:%d/%m/%Y} à {e['lieu']}", "Helvetica-Bold", 8.5)
    txt(X1 - 4, 668, f"Effectif : {b['stats']['effectif']}", "Helvetica-Bold", 9, "r")
    txt(X1 - 4, 656, f"N° étudiant : {e['matricule']}", "Helvetica", 7.5, "r")

    # UE
    y = 646
    CC, CM = 404, 482                                  # colonnes coefficient / moyenne
    pair = b["sem_idx"] == 2
    for u, (code, titre, credits, ecs, _t) in enumerate(b["maquette"]):
        ue = b["ues"][u]
        h = 11 + 10 * len(ecs) + 22
        box(X0, y - h, X1 - X0, h)
        cv.line(CC, y, CC, y - 11 - 10 * len(ecs))
        cv.line(CM, y, CM, y - 11 - 10 * len(ecs))
        txt(X0 + 2, y - 8.5, f"{titre} ({code})", "Helvetica-Bold", 7.5)
        txt((CC + CM) / 2, y - 8.5, "Coefficient", "Helvetica", 7, "c")
        txt((CM + X1) / 2, y - 8.5, "Moyenne / 20", "Helvetica", 7, "c")
        yy = y - 11
        for (ecode, enom, coef), m in zip(ecs, ue["marks"]):
            cv.line(X0, yy, X1, yy)
            txt(X0 + 2, yy - 7.5, f"{ecode}: {enom}", "Helvetica", 7)
            txt((CC + CM) / 2, yy - 7.5, fmt(coef), "Helvetica", 7.5, "c")
            txt((CM + X1) / 2, yy - 7.5, fmt(m), "Helvetica", 7.5, "c")
            yy -= 10
        cv.line(X0, yy, X1, yy)
        # ligne de synthèse de l'UE
        base_y = yy - 22
        for xs in (220, 296, 372):
            cv.line(xs, yy, xs, base_y)
        txt(X0 + 2, yy - 10, f"Moyenne_{code} : {fmt(ue['moy'])} / 20", "Helvetica-Bold", 8)
        txt(X0 + 4, yy - 19, "UE validée" if ue["moy"] >= 10 else "UE non validée", "Helvetica", 6.5)
        txt(258, yy - 9, "Session", "Helvetica-Bold", 7.5, "c")
        txt(258, yy - 18, ue["ses"], "Helvetica", 6.5, "c")
        txt(334, yy - 9, "Crédits", "Helvetica-Bold", 7.5, "c")
        txt(334, yy - 18, f"{credits:.1f}", "Helvetica", 7.5, "c")
        if pair:
            txt((372 + X1) / 2, yy - 9, "Absence(s)", "Helvetica-Bold", 7.5, "c")
            txt((372 + X1) / 2, yy - 18, str(ue["abs"]), "Helvetica", 7.5, "c")
        else:
            txt((372 + X1) / 2, yy - 9, f"Moyenne classe : {fmt(b['stats']['ue_moy'][u])}", "Helvetica", 7, "c")
            txt((372 + X1) / 2, yy - 18, f"Moyenne max : {fmt(b['stats']['ue_max'][u])}", "Helvetica", 7, "c")
        y -= h

    # Synthèse du semestre
    box(X0, y - 16, X1 - X0, 16)
    txt(X0 + 4, y - 11, f"Moyenne semestre : {fmt(res['moy'])}", "Helvetica-Bold", 8.5)
    if not pair:
        txt(258, y - 11, f"Rang  {b['stats']['rang']}/{b['stats']['effectif']}", "Helvetica", 8, "c")
        cv.line(220, y, 220, y - 16)
        cv.line(296, y, 296, y - 16)
    else:
        cv.line(296, y, 296, y - 16)
    txt(428, y - 11, f"Moyenne de la classe : {fmt(b['stats']['classe'])}", "Helvetica-Bold", 8.5, "c")
    y -= 16
    box(X0, y - 14, X1 - X0, 14)
    cv.line(296, y, 296, y - 14)
    txt(X0 + 4 + 80, y - 10, f"{res['credits']:.1f}/30", "Helvetica-Bold", 8.5, "c")
    txt(428, y - 10, f"SEMESTRE {b['sem_num']} {'VALIDÉ' if res['valide'] else 'NON VALIDÉ'}", "Helvetica-Bold", 8.5, "c")
    y -= 14
    if pair:
        an = b["annuel"]
        box(X0, y - 14, X1 - X0, 14)
        box(X0, y - 30, X1 - X0, 16)
        for xs in (X0 + 130, X0 + 260):
            cv.line(xs, y, xs, y - 30)
        txt(X0 + 65, y - 10, "Total crédits", "Helvetica-Bold", 8, "c")
        txt(X0 + 195, y - 10, "Moyenne annuelle", "Helvetica-Bold", 8, "c")
        txt((X0 + 260 + X1) / 2, y - 10, "Décision du conseil", "Helvetica-Bold", 8, "c")
        txt(X0 + 65, y - 25, f"{an['total']:.1f}/60", "Helvetica", 8.5, "c")
        txt(X0 + 195, y - 25, fmt(an["moy"]), "Helvetica", 8.5, "c")
        txt((X0 + 260 + X1) / 2, y - 25, an["decision"], "Helvetica", 7.5, "c")
        y -= 30

    # Pied
    y = min(y - 22, 190)
    txt(X1 - 10, y, f"Fait à Dakar, le {b['date']:%d/%m/%Y}", "Helvetica", 8.5, "r")
    txt(X1 - 10, y - 11, "Le Chef de Département", "Helvetica", 8.5, "r")
    txt(X1 - 10, y - 24, "[sans signature ni cachet]", "Helvetica-Oblique", 6.5, "r")
    txt(X0, 46, "**Il ne peut être délivré qu'un seul exemplaire de ce relevé", "Helvetica", 7)
    cv.setFillGray(0.45)
    txt(X0, 30, "Document FICTIF généré automatiquement (jeu de données de test MadeArchive) — sans valeur officielle.",
        "Helvetica-Oblique", 6.5)
    cv.showPage()
    cv.save()


# ════════════════════════════════════════════════════════════════════════════
# 5. Orchestration (multiprocessus, reprise, manifeste)
# ════════════════════════════════════════════════════════════════════════════

ENTETE = ["chemin", "departement", "annee_academique", "formation", "classe", "parcours", "semestre", "session",
          "matricule", "nom", "prenoms", "date_naissance", "lieu_naissance", "moyenne_semestre",
          "credits_semestre", "validation_semestre", "decision_annuelle", "date_edition"]


def ligne_manifeste(b: dict, rel: str) -> list:
    c, e, r = b["cohorte"], b["etu"], b["res"]
    return [rel, c.dept, c.annee_ac, c.form, c.classe, c.parcours, f"S{b['sem_num']}", b["session"], e["matricule"],
            e["nom"], e["prenoms"], f"{e['naissance']:%Y-%m-%d}", e["lieu"], fmt(r["moy"]), f"{r['credits']:.1f}",
            "VALIDE" if r["valide"] else "NON_VALIDE", b.get("annuel", {}).get("decision", ""), f"{b['date']:%Y-%m-%d}"]


def travailler(args):
    c, base, graine, sortie, mode = args
    marqueur = Path(sortie) / "_manifestes" / f"{c.cle}.csv"
    if mode == "ecrire" and marqueur.exists():
        return c.cle, sum(1 for _ in open(marqueur, encoding="utf-8")) - 1, True
    bulletins = simuler_cohorte(c, base, graine)
    if mode == "compter":
        return c.cle, len(bulletins), False
    dossier = Path(sortie) / c.dossier
    dossier.mkdir(parents=True, exist_ok=True)
    lignes = []
    for b in bulletins:
        nom = nom_fichier(b)
        rendre(b, dossier / nom)
        lignes.append(ligne_manifeste(b, f"{c.dossier}/{nom}"))
    tmp = marqueur.with_suffix(".tmp")
    with open(tmp, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(ENTETE)
        w.writerows(lignes)
    os.replace(tmp, marqueur)                       # présence du fichier = classe terminée
    return c.cle, len(lignes), False


def compter(cohortes, base, graine, workers) -> int:
    with ProcessPoolExecutor(workers) as ex:
        return sum(n for _k, n, _s in ex.map(travailler, [(c, base, graine, "", "compter") for c in cohortes],
                                             chunksize=8))


def calibrer(cohortes, cible, graine, workers) -> float:
    """Trouve l'effectif de base qui donne au moins `cible` PDF (comptage exact par simulation)."""
    echantillon = cohortes[:: max(1, len(cohortes) // 60)]
    n_ech = compter(echantillon, 100.0, graine, workers)
    poids = sum(c.poids for c in echantillon)
    docs_par_poids = n_ech / (poids * 100.0)
    base = cible * MARGE_CIBLE / (docs_par_poids * sum(c.poids for c in cohortes))
    for _ in range(4):
        total = compter(cohortes, base, graine, workers)
        if total >= cible:
            return base
        base *= (cible / total) * 1.002
    return base


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sortie", default=str(Path.home() / "MadeArchive_bulletins"), help="dossier de sortie")
    ap.add_argument("--cible", type=int, default=1_050_000, help="nombre minimal de PDF visé (défaut 1 050 000)")
    ap.add_argument("--annees", type=int, default=20, help="nombre d'années académiques (défaut 20)")
    ap.add_argument("--derniere-annee", type=int, default=2025, help="année de début de la dernière (2025 = 2025-2026)")
    ap.add_argument("--departements", nargs="*", default=list(DEPARTEMENTS), choices=list(DEPARTEMENTS))
    ap.add_argument("--effectif-base", type=float, help="effectif de base d'une classe (désactive le calcul par --cible)")
    ap.add_argument("--limite-classes", type=int, help="ne traiter que les N premières classes (essais)")
    ap.add_argument("--workers", type=int, default=os.cpu_count() or 4)
    ap.add_argument("--graine", type=int, default=2026)
    ap.add_argument("--dry-run", action="store_true", help="compte exactement les PDF, n'écrit rien")
    a = ap.parse_args()

    annees = list(range(a.derniere_annee - a.annees + 1, a.derniere_annee + 1))
    cohortes = lister_cohortes(annees, a.departements)
    if a.limite_classes:
        cohortes = cohortes[: a.limite_classes]
    print(f"{len(cohortes)} classes ({annees[0]}-{annees[0] + 1} … {annees[-1]}-{annees[-1] + 1}), "
          f"{len(a.departements)} départements.")

    if a.effectif_base:
        base = a.effectif_base
    else:
        t = time.time()
        base = calibrer(cohortes, a.cible, a.graine, a.workers)
        print(f"Effectif de base calibré : {base:.1f} étudiants/classe (poids : L1 ×1,5 … M2 ×0,5) "
              f"[{time.time() - t:.0f}s]")
    total = compter(cohortes, base, a.graine, a.workers)
    print(f"Nombre exact de PDF : {total:,}".replace(",", " "))
    if a.dry_run:
        print(f"Espace disque estimé : ~{total * 8192 / 1e9:.1f} Go (~8 Ko par PDF sur APFS).")
        return 0
    if not a.effectif_base and total < a.cible:
        print(f"ATTENTION : {total} < cible {a.cible}.", file=sys.stderr)

    sortie = Path(a.sortie)
    (sortie / "_manifestes").mkdir(parents=True, exist_ok=True)
    taches = [(c, base, a.graine, str(sortie), "ecrire") for c in cohortes]
    fait, debut, deja = 0, time.time(), 0
    with ProcessPoolExecutor(a.workers) as ex:
        for i, (_cle, n, saute) in enumerate(ex.map(travailler, taches, chunksize=1), 1):
            fait += n
            deja += n if saute else 0
            if i % 25 == 0 or i == len(taches):
                ecoule = max(1e-9, time.time() - debut)
                rythme = (fait - deja) / ecoule
                reste = (total - fait) / rythme if rythme > 0 else 0
                print(f"  {i}/{len(taches)} classes — {fait:,} PDF — {rythme:,.0f} PDF/s — "
                      f"reste ~{reste / 60:.0f} min".replace(",", " "), flush=True)

    manifeste = sortie / "manifeste.csv"
    with open(manifeste, "w", newline="", encoding="utf-8") as out:
        w = csv.writer(out)
        w.writerow(ENTETE)
        for c in cohortes:
            with open(sortie / "_manifestes" / f"{c.cle}.csv", encoding="utf-8", newline="") as f:
                r = csv.reader(f)
                next(r)
                w.writerows(r)
    print(f"Terminé : {fait:,} PDF dans {sortie}\nManifeste : {manifeste}".replace(",", " "))
    return 0


if __name__ == "__main__":
    sys.exit(main())
