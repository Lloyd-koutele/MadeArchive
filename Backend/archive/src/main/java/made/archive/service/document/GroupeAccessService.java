package made.archive.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import made.archive.dto.GroupeMembresDto;
import made.archive.dto.UniteOrganisationnelleDto;
import made.archive.entite.*;
import made.archive.exception.BusinessException;
import made.archive.repository.*;
import made.archive.service.audit.AuditLogService;
import made.archive.service.organisation.UniteOrganisationnelleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupeAccessService
{
    private final GroupeAccessRepository groupeAccessRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final UniteOrganisationnelleService uniteOrganisationnelleService;

    /**
     * Liste tous les membres ayant accès au document, ouvert à N'IMPORTE QUEL
     * membre du groupe (pas seulement l'uploadeur — voir verifierMembre) : tout
     * membre a le droit de savoir qui d'autre a accès au document. La gestion
     * (ajout/retrait) est elle réservée aux membres qui sont AUSSI éditeurs
     * (voir verifierPeutGererGroupe) — d'où peutGerer, qui dit au client s'il
     * doit afficher ces contrôles pour CE demandeur.
     */
    @Transactional(readOnly = true)
    public GroupeMembresDto getMembres(UUID documentId, UUID demandeurId)
    {
        Document document = getDocumentPrive(documentId);
        verifierMembre(document.getGroupe(), demandeurId);

        return GroupeMembresDto.builder()
            .membres(document.getGroupe().getMembres())
            .uploadeurId(document.getUploadedBy().getId())
            .peutGerer(peutGererGroupe(document.getGroupe(), demandeurId))
            .build();
    }

    /**
     * Ajoute un membre au groupe du document.
     * Réservé à un membre ayant AUSSI le rôle éditeur — pas à n'importe quel
     * membre du groupe (voir verifierPeutGererGroupe). L'uploadeur n'est plus
     * seul à pouvoir gérer : ça évite qu'un groupe se retrouve figé si
     * l'uploadeur quitte l'unité organisationnelle (voir Javadoc de la classe).
     */
    @Transactional
    public void ajouterMembre(UUID documentId, UUID demandeurId, UUID nouveauMembreId)
    {
        Document document = getDocumentPrive(documentId);
        GroupeAccess groupe = document.getGroupe();
        verifierPeutGererGroupe(groupe, demandeurId);

        // Vérifier que le nouveau membre existe
        User nouveauMembre = userRepository.findById(nouveauMembreId)
            .orElseThrow(() -> new BusinessException(
                "Utilisateur introuvable : " + nouveauMembreId));

        // Vérifier qu'il n'est pas déjà membre
        boolean dejaPresent = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(nouveauMembreId));
        if (dejaPresent)
        {
            throw new BusinessException(
                "Cet utilisateur est déjà membre du groupe");
        }

        groupe.getMembres().add(nouveauMembre);
        groupeAccessRepository.save(groupe);
        log.info("[Groupe] Membre {} ajouté au groupe {} par {}",
                 nouveauMembreId, groupe.getId(), demandeurId);

        auditLogService.log(userRepository.findById(demandeurId).orElse(null),
            AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.DOCUMENT, documentId.toString(),
            document.getUniteOrganisationnelle() != null ? document.getUniteOrganisationnelle().getId() : null,
            nouveauMembre.getEmail() + " ajouté au groupe d'accès du document \"" + document.getTitre() + "\"",
            true);
    }

    /**
     * Retire un membre du groupe du document.
     * Réservé à un membre ayant AUSSI le rôle éditeur (voir verifierPeutGererGroupe).
     * L'uploadeur lui-même ne peut jamais être retiré — il reste TOUJOURS
     * membre de son propre groupe, quel que soit qui gère le groupe.
     *
     * Conséquence : le groupe ne peut plus jamais devenir vide par cette voie
     * (l'uploadeur y est ajouté dès la création — voir DocumentUploadeService —
     * et ne peut plus en être retiré). Le document ne repasse donc plus
     * automatiquement en PUBLIC par simple retrait de membres.
     */
    @Transactional
    public void retirerMembre(UUID documentId, UUID demandeurId, UUID membreARetirerID)
    {
        Document document = getDocumentPrive(documentId);
        GroupeAccess groupe = document.getGroupe();
        verifierPeutGererGroupe(groupe, demandeurId);

        if (membreARetirerID.equals(document.getUploadedBy().getId()))
        {
            throw new BusinessException(
                "L'éditeur ayant archivé ce document ne peut pas être retiré de son groupe d'accès");
        }

        // Vérifier que le membre à retirer est bien dans le groupe
        boolean estMembre = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(membreARetirerID));
        if (!estMembre)
        {
            throw new BusinessException(
                "Cet utilisateur n'est pas membre du groupe");
        }

        String membreRetireEmail = userRepository.findById(membreARetirerID)
            .map(User::getEmail).orElse(membreARetirerID.toString());

        groupe.getMembres().removeIf(m -> m.getId().equals(membreARetirerID));
        groupeAccessRepository.save(groupe);

        Long uoContexte = document.getUniteOrganisationnelle() != null
            ? document.getUniteOrganisationnelle().getId() : null;
        User acteur = userRepository.findById(demandeurId).orElse(null);

        log.info("[Groupe] Membre {} retiré du groupe {} par {}",
                 membreARetirerID, groupe.getId(), demandeurId);

        auditLogService.log(acteur, AuditAction.GROUPE_MEMBRE_RETIRE, AuditCible.DOCUMENT,
            documentId.toString(), uoContexte,
            membreRetireEmail + " retiré du groupe d'accès de \"" + document.getTitre() + "\"",
            true);
    }

    /**
     * Liste les utilisateurs proposables comme membres du groupe d'accès d'un
     * document privé : les collègues de la PROPRE UO du demandeur (jamais de
     * toute la plateforme — cohérent avec le périmètre par UO appliqué partout
     * ailleurs), plus tous les ADMIN globaux (rattachés à aucune UO, mais
     * légitimes sur tout document par leur rôle). Ne renvoie jamais ceux déjà
     * membres.
     *
     * Avant ce correctif : userRepository.findAll() exposait l'annuaire complet
     * de la plateforme (toutes UO confondues) à n'importe quel éditeur.
     */
    @Transactional(readOnly = true)
    public List<User> getUtilisateursDisponibles(UUID documentId, UUID demandeurId)
    {
        Document document = getDocumentPrive(documentId);
        verifierPeutGererGroupe(document.getGroupe(), demandeurId);

        List<UUID> membresIds = document.getGroupe().getMembres().stream()
            .map(User::getId)
            .toList();

        User demandeur = userRepository.findById(demandeurId)
            .orElseThrow(() -> new BusinessException("Utilisateur introuvable : " + demandeurId));

        // Map plutôt que Set/List : dédoublonne par id si un même utilisateur
        // (ex. un ADMIN_UO admin ET membre de sa propre UO) apparaîtrait des
        // deux côtés, tout en préservant un ordre stable.
        Map<UUID, User> candidats = new LinkedHashMap<>();

        // 1. Collègues de la propre UO du demandeur — un ADMIN global n'a
        //    aucune UO active (voir UniteOrganisationnelleService.changerUOUtilisateur),
        //    cette étape est alors simplement sans effet pour lui.
        Optional<UniteOrganisationnelleDto> uoActuelle =
            uniteOrganisationnelleService.getUOActuelleUser(demandeurId);
        if (uoActuelle.isPresent())
        {
            List<User> collegues = uniteOrganisationnelleService
                .getUtilisateursDeUO(uoActuelle.get().getId(), demandeur);
            collegues.forEach(u -> candidats.put(u.getId(), u));
        }

        // 2. Tous les ADMIN globaux.
        userRepository.findByRoleName(Role_Name.ADMIN)
            .forEach(u -> candidats.put(u.getId(), u));

        return candidats.values().stream()
            .filter(u -> !membresIds.contains(u.getId()))
            .toList();
    }

    // -------------------------------------------------------------------------
    // Méthodes privées
    // -------------------------------------------------------------------------

    private Document getDocumentPrive(UUID documentId)
    {
        Document document = documentRepository.findById(documentId)
            .orElseThrow(() -> new BusinessException(
                "Document introuvable : " + documentId));

        if (document.getAccess() != TypeAccess.PRIVE || document.getGroupe() == null)
        {
            throw new BusinessException(
                "Ce document n'est pas un document privé");
        }

        return document;
    }

    private void verifierMembre(GroupeAccess groupe, UUID userId)
    {
        boolean estMembre = groupe.getMembres().stream()
            .anyMatch(m -> m.getId().equals(userId));
        if (!estMembre)
        {
            throw new BusinessException(
                "Accès refusé : vous n'êtes pas membre de ce groupe");
        }
    }

    /**
     * Gestion du groupe (ajout/retrait de membres) réservée à un membre ayant
     * AUSSI le rôle éditeur — pas à n'importe quel membre du groupe (voir
     * getMembres/verifierMembre pour la simple consultation, ouverte à tous).
     * Évite qu'un groupe se retrouve définitivement figé si l'uploadeur quitte
     * l'unité organisationnelle : les autres membres-éditeurs peuvent prendre
     * le relais.
     */
    private boolean peutGererGroupe(GroupeAccess groupe, UUID userId)
    {
        boolean estMembre = groupe.getMembres().stream().anyMatch(m -> m.getId().equals(userId));
        if (!estMembre)
        {
            return false;
        }
        return userRepository.findById(userId)
            .map(u -> u.getRoles().stream().anyMatch(r -> r.getName() == Role_Name.EDITOR))
            .orElse(false);
    }

    private void verifierPeutGererGroupe(GroupeAccess groupe, UUID demandeurId)
    {
        if (!peutGererGroupe(groupe, demandeurId))
        {
            throw new BusinessException(
                "Seul un membre de ce groupe ayant le rôle éditeur peut le gérer");
        }
    }
}