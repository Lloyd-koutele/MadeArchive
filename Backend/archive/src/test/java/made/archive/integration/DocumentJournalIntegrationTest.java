package made.archive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import made.archive.entite.AuditAction;
import made.archive.entite.AuditCible;
import made.archive.entite.JournalAudit;
import made.archive.repository.JournalAuditRepository;

/** Le journal d'un document = ses entrées + celles de SON groupe d'accès, jamais celles d'un autre document/groupe. */
@Tag("integration")
@SpringBootTest(classes = DocumentJournalIntegrationTest.Config.class,
                 webEnvironment = SpringBootTest.WebEnvironment.NONE,
                 properties = {
                     "spring.jpa.hibernate.ddl-auto=create-drop",
                     "spring.flyway.enabled=false",
                     "spring.sql.init.mode=never"
                 })
@Testcontainers
class DocumentJournalIntegrationTest
{
    @Configuration
    @EnableAutoConfiguration(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        DataRedisAutoConfiguration.class,
        DataRedisReactiveAutoConfiguration.class
    })
    @EntityScan(basePackages = "made.archive.entite")
    @EnableJpaRepositories(basePackages = "made.archive.repository")
    static class Config {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private JournalAuditRepository repository;

    private JournalAudit entree(AuditAction action, AuditCible cible, String cibleId, long secondes)
    {
        return repository.save(JournalAudit.builder()
            .horodatage(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(secondes))
            .action(action).cibleType(cible).cibleId(cibleId)
            .description(action.name()).succes(true).build());
    }

    @Test
    void journalDuDocument_sesEntreesPlusCellesDeSonGroupe_plusRecentDAbord()
    {
        String doc = UUID.randomUUID().toString();
        String autreDoc = UUID.randomUUID().toString();

        entree(AuditAction.DOCUMENT_UPLOAD_REUSSI, AuditCible.DOCUMENT, doc, 1);
        entree(AuditAction.DOCUMENT_CONSULTE, AuditCible.DOCUMENT, doc, 2);
        entree(AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.GROUPE_ACCES, "42", 3);
        entree(AuditAction.GROUPE_MEMBRE_AJOUTE, AuditCible.GROUPE_ACCES, "99", 4);          // autre groupe
        entree(AuditAction.DOCUMENT_TELECHARGE, AuditCible.DOCUMENT, autreDoc, 5);           // autre document
        entree(AuditAction.DOCUMENT_TELECHARGE, AuditCible.DOCUMENT, doc, 6);
        entree(AuditAction.DOCUMENT_CONSULTE, AuditCible.DOCUMENT, "42", 7);                 // même id texte que le groupe, autre type

        Page<JournalAudit> page = repository.findJournalDocument(doc, "42", PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(JournalAudit::getAction).containsExactly(
            AuditAction.DOCUMENT_TELECHARGE, AuditAction.GROUPE_MEMBRE_AJOUTE,
            AuditAction.DOCUMENT_CONSULTE, AuditAction.DOCUMENT_UPLOAD_REUSSI);

        // Document sans groupe ("") : uniquement ses propres entrées
        assertThat(repository.findJournalDocument(doc, "", PageRequest.of(0, 10)).getContent())
            .extracting(JournalAudit::getAction)
            .containsExactly(AuditAction.DOCUMENT_TELECHARGE, AuditAction.DOCUMENT_CONSULTE, AuditAction.DOCUMENT_UPLOAD_REUSSI);

        // Pagination
        Page<JournalAudit> p1 = repository.findJournalDocument(doc, "42", PageRequest.of(1, 3));
        assertThat(p1.getTotalElements()).isEqualTo(4);
        assertThat(p1.getContent()).hasSize(1);
    }
}
