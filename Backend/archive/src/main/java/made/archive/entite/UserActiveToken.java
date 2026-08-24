package made.archive.entite;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Column;

@Entity
@Table(name = "user_active_token")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserActiveToken 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @ManyToOne
    @JsonIgnore
    private User user;

    @Column(nullable = false, length = 512)
    private String accessToken;

    @Column(nullable = false)
    private Instant expiresAt;
   
}
