package made.archive.service.document;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import made.archive.dto.DocumentExportRow;
import made.archive.entite.SortFinal;

/**
 * Construit le manifest.xml d'un paquet SEDA 2.1 (message ArchiveTransfer) à partir des
 * documents d'un export — pur calcul, sans accès base ni stockage (voir
 * SedaExportGenerationService, qui fournit les données et valide le résultat contre le XSD).
 *
 * Arbre d'ArchiveUnit : UO (une unité par niveau du chemin, RecordGrp) > activité du
 * plan de classement (Series puis Subseries, si le type du document en a une) > dossier
 * (File, un niveau par dossier parent, seulement si l'option "séparer par dossier" est
 * active) > document (Item). Un document sans activité n'a pas de niveau "activité" ;
 * un dossier dont les documents relèvent de plusieurs activités apparaît sous chacune
 * (les unités sont distinctes — un ArchiveUnit n'a qu'un seul parent dans un SIP).
 *
 * Écrit à la main avec StAX (aucune dépendance JAXB). L'ordre des éléments suit le XSD
 * SEDA 2.1 — toute dérive est de toute façon détectée par la validation XSD en aval.
 */
public class SedaManifestBuilder
{
    public static final String NS = "fr:gouv:culture:archivesdefrance:seda:v2.1";

    public record Contexte(String messageId, Instant date, String commentaire, String accordVersement,
                           String serviceArchives, String serviceVersant, String prefixeRegleDua) {}

    public record Noeud(Long id, String code, String libelle, Long parentId) {}

    public record DossierInfo(Long id, String nom, Long parentId) {}

    /** Un document déjà écrit dans content/ — sha256/taille mesurés sur les octets réellement exportés. */
    public record Entree(DocumentExportRow doc, String sha256, long taille, String uri,
                         String cheminUO, Map<String, String> metadonnees) {}

    private static final class Unite
    {
        final String id;
        final String niveau;
        final String titre;
        final Map<String, Unite> enfantsParCle = new LinkedHashMap<>();
        final List<Unite> enfants = new ArrayList<>();
        Entree entree;
        String objetId;

        Unite(String id, String niveau, String titre)
        {
            this.id = id;
            this.niveau = niveau;
            this.titre = titre;
        }

        Unite enfant(String cle, java.util.function.Supplier<Unite> creation)
        {
            return enfantsParCle.computeIfAbsent(cle, k -> {
                Unite u = creation.get();
                enfants.add(u);
                return u;
            });
        }
    }

    private int compteurUnites = 0;

    public byte[] construire(Contexte ctx, List<Entree> entrees, Map<Long, Noeud> noeuds,
                             Map<Long, DossierInfo> dossiers, boolean avecDossiers)
    {
        Unite racine = new Unite("AU_0", "", "");
        int numObjet = 0;
        List<Entree> ordonnees = new ArrayList<>();

        for (Entree e : entrees)
        {
            Unite courant = racine;
            String chemin = "";

            if (e.cheminUO() != null && !e.cheminUO().isBlank())
            {
                for (String segment : e.cheminUO().split("/"))
                {
                    chemin += "/" + segment;
                    final String titre = segment;
                    courant = courant.enfant("UO" + chemin, () -> nouvelle("RecordGrp", titre));
                }
            }

            Long noeudId = e.doc().planClassementNoeudId();
            if (noeudId != null && noeuds.containsKey(noeudId))
            {
                List<Noeud> lignee = new ArrayList<>();
                for (Noeud n = noeuds.get(noeudId); n != null; n = n.parentId() != null ? noeuds.get(n.parentId()) : null)
                {
                    lignee.add(0, n);
                }
                for (int i = 0; i < lignee.size(); i++)
                {
                    Noeud n = lignee.get(i);
                    final String niveau = i == 0 ? "Series" : "Subseries";
                    chemin += "/A" + n.id();
                    courant = courant.enfant("ACT" + chemin, () -> nouvelle(niveau, n.code() + " " + n.libelle()));
                }
            }

            Long dossierId = e.doc().dossierId();
            if (avecDossiers && dossierId != null && dossiers.containsKey(dossierId))
            {
                List<DossierInfo> lignee = new ArrayList<>();
                for (DossierInfo d = dossiers.get(dossierId); d != null; d = d.parentId() != null ? dossiers.get(d.parentId()) : null)
                {
                    lignee.add(0, d);
                }
                for (DossierInfo d : lignee)
                {
                    chemin += "/D" + d.id();
                    courant = courant.enfant("DOS" + chemin, () -> nouvelle("File", d.nom()));
                }
            }

            Unite doc = nouvelle("Item", e.doc().titre());
            doc.entree = e;
            doc.objetId = "BDO_" + (++numObjet);
            courant.enfants.add(doc);
            ordonnees.add(e);
        }

        try
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Ecrivain w = new Ecrivain(out);
            w.debut("ArchiveTransfer", true);
            w.texte("Comment", ctx.commentaire());
            w.texte("Date", ctx.date().toString());
            w.texte("MessageIdentifier", ctx.messageId());
            w.texte("ArchivalAgreement", ctx.accordVersement());
            w.vide("CodeListVersions");
            w.debut("DataObjectPackage", false);

            ecrireObjets(w, racine);

            w.debut("DescriptiveMetadata", false);
            for (Unite u : racine.enfants)
            {
                ecrireUnite(w, u, ctx);
            }
            w.fin();

            w.debut("ManagementMetadata", false);
            w.texte("OriginatingAgencyIdentifier", ctx.serviceVersant());
            w.fin();

            w.fin(); // DataObjectPackage
            w.debut("ArchivalAgency", false);
            w.texte("Identifier", ctx.serviceArchives());
            w.fin();
            w.debut("TransferringAgency", false);
            w.texte("Identifier", ctx.serviceVersant());
            w.fin();
            w.fin(); // ArchiveTransfer
            w.close();
            return out.toByteArray();
        }
        catch (XMLStreamException ex)
        {
            throw new IllegalStateException("Écriture du manifest SEDA impossible", ex);
        }
    }

    private Unite nouvelle(String niveau, String titre)
    {
        return new Unite("AU_" + (++compteurUnites), niveau, titre);
    }

    private void ecrireObjets(Ecrivain w, Unite u) throws XMLStreamException
    {
        if (u.entree != null)
        {
            Entree e = u.entree;
            w.debut("BinaryDataObject", false);
            w.attribut("id", u.objetId);
            w.texte("DataObjectVersion", "BinaryMaster_1");
            w.texte("Uri", e.uri());
            w.debutAvecAttribut("MessageDigest", "algorithm", "SHA-256");
            w.contenu(e.sha256());
            w.fin();
            w.texte("Size", Long.toString(e.taille()));
            w.debut("FormatIdentification", false);
            w.texte("MimeType", "application/pdf");
            w.fin();
            w.debut("FileInfo", false);
            w.texte("Filename", nomFichier(e));
            w.fin();
            w.fin();
        }
        for (Unite enfant : u.enfants)
        {
            ecrireObjets(w, enfant);
        }
    }

    private String nomFichier(Entree e)
    {
        String titre = e.doc().titre() != null ? e.doc().titre() : e.doc().id().toString();
        return titre.toLowerCase().endsWith(".pdf") ? titre : titre + ".pdf";
    }

    private void ecrireUnite(Ecrivain w, Unite u, Contexte ctx) throws XMLStreamException
    {
        w.debut("ArchiveUnit", false);
        w.attribut("id", u.id);

        if (u.entree != null)
        {
            ecrireGestion(w, u.entree, ctx);
        }

        w.debut("Content", false);
        w.texte("DescriptionLevel", u.niveau);
        w.texte("Title", u.titre == null || u.titre.isBlank() ? "(sans titre)" : u.titre);

        if (u.entree != null)
        {
            DocumentExportRow d = u.entree.doc();
            w.texte("OriginatingAgencyArchiveUnitIdentifier", d.id().toString());
            String description = description(u.entree);
            if (!description.isEmpty())
            {
                w.texte("Description", description);
            }
            if (d.typeDocumentNom() != null)
            {
                ecrireMotCle(w, d.typeDocumentNom(), "genreform");
            }
            if (d.createAt() != null)
            {
                LocalDate jour = d.createAt().toLocalDate();
                w.texte("StartDate", jour.toString());
                w.texte("EndDate", jour.toString());
            }
        }
        w.fin(); // Content

        if (u.entree != null)
        {
            w.debut("DataObjectReference", false);
            w.texte("DataObjectReferenceId", u.objetId);
            w.fin();
        }
        for (Unite enfant : u.enfants)
        {
            ecrireUnite(w, enfant, ctx);
        }
        w.fin();
    }

    private void ecrireMotCle(Ecrivain w, String contenu, String type) throws XMLStreamException
    {
        w.debut("Keyword", false);
        w.texte("KeywordContent", contenu);
        w.texte("KeywordType", type);
        w.fin();
    }

    /**
     * Règle de gestion d'un document : durée d'utilité administrative + sort final.
     * SEDA 2.1 ne connaît que Keep/Destroy : TRIER n'a pas d'équivalent et est exporté
     * "Keep" (rien ne doit être détruit tant qu'un tri n'a pas eu lieu). Durée indéfinie :
     * pas de Rule, juste FinalAction.
     */
    private void ecrireGestion(Ecrivain w, Entree e, Contexte ctx) throws XMLStreamException
    {
        DocumentExportRow d = e.doc();
        w.debut("Management", false);
        w.debut("AppraisalRule", false);
        if (d.retentionYears() != null)
        {
            w.texte("Rule", ctx.prefixeRegleDua() + d.retentionYears() + "ANS");
            if (d.createAt() != null)
            {
                w.texte("StartDate", d.createAt().toLocalDate().toString());
            }
        }
        w.texte("FinalAction", d.sortFinal() == SortFinal.DETRUIRE ? "Destroy" : "Keep");
        w.fin();
        w.fin();
    }

    private String description(Entree e)
    {
        if (e.metadonnees() == null || e.metadonnees().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        e.metadonnees().forEach((k, v) -> {
            if (v != null && !v.isBlank())
            {
                if (sb.length() > 0) sb.append(" ; ");
                sb.append(k).append(" : ").append(v);
            }
        });
        return sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
    }

    /** XMLStreamWriter + indentation, et nettoyage des caractères interdits en XML 1.0. */
    private static final class Ecrivain
    {
        private final XMLStreamWriter w;
        private final Deque<Boolean> aEnfants = new ArrayDeque<>();

        Ecrivain(ByteArrayOutputStream out) throws XMLStreamException
        {
            w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
        }

        void debut(String nom, boolean racine) throws XMLStreamException
        {
            prefixe();
            w.writeStartElement(nom);
            if (racine) w.writeDefaultNamespace(NS);
            aEnfants.push(false);
        }

        void debutAvecAttribut(String nom, String attr, String val) throws XMLStreamException
        {
            debut(nom, false);
            w.writeAttribute(attr, val);
        }

        void attribut(String nom, String valeur) throws XMLStreamException
        {
            w.writeAttribute(nom, valeur);
        }

        void contenu(String t) throws XMLStreamException
        {
            // Pas d'indentation avant la fermeture : l'élément n'a que du texte (pile inchangée = false).
            w.writeCharacters(nettoyer(t));
        }

        void texte(String nom, String t) throws XMLStreamException
        {
            prefixe();
            w.writeStartElement(nom);
            w.writeCharacters(nettoyer(t));
            w.writeEndElement();
        }

        void vide(String nom) throws XMLStreamException
        {
            prefixe();
            w.writeEmptyElement(nom);
        }

        void fin() throws XMLStreamException
        {
            Boolean avait = aEnfants.pop();
            if (Boolean.TRUE.equals(avait))
            {
                w.writeCharacters("\n" + "  ".repeat(aEnfants.size()));
            }
            w.writeEndElement();
        }

        void close() throws XMLStreamException
        {
            w.writeEndDocument();
            w.close();
        }

        private void prefixe() throws XMLStreamException
        {
            w.writeCharacters("\n" + "  ".repeat(aEnfants.size()));
            if (!aEnfants.isEmpty())
            {
                aEnfants.pop();
                aEnfants.push(true);
            }
        }

        private static String nettoyer(String t)
        {
            if (t == null) return "";
            StringBuilder sb = new StringBuilder(t.length());
            t.codePoints().forEach(cp -> {
                boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD
                    || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
                if (ok) sb.appendCodePoint(cp);
            });
            return sb.toString();
        }
    }
}
