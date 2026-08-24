package made.archive.entite;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDate;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name="incidents")
@Entity
public class Incident 
{
    @Id
    @GeneratedValue()
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(nullable=false,length=20)
    private LocalDate createAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false, length=20)
    private IncidentStatus status;

    @Column(nullable = false, length = 255)
    private String nomDocument;

    @ManyToOne
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;
}
