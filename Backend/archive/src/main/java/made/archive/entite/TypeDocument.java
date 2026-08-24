package made.archive.entite;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import jakarta.persistence.CascadeType;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name="type_documents")
@Entity
public class TypeDocument 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "La signature hash est obligatoire")
    @Column(nullable = false, length = 100)
    private String nom;

    @NotNull
    @JoinColumn(name = "retention_id", nullable = false)
    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    private Retention retention;

    @OneToMany(mappedBy = "typeDocument", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<MetaData> metaData;

    @OneToMany(mappedBy = "typeDocument", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private List<Document> documents;

    @NotNull
    @JoinColumn(name = "user_id", nullable = false)
    @ManyToOne
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unite_organisationnelle_id", nullable = false)
    @JsonIgnore
    private UniteOrganisationnelle uniteOrganisationnelle;

    @Column(name = "extraction_regex_json", columnDefinition = "TEXT", nullable = true)
    private String extractionRegexJson;
    
    @Column(name = "regex_generated", nullable = false)
    private Boolean regexGenerated = false;

    
    @JsonIgnore
    public Map<String, String> getExtractionRegexMap()
    {
        if (extractionRegexJson == null || extractionRegexJson.isBlank())
        {
            return Map.of();
        }
        
        try
        {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(
                extractionRegexJson,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
            );
        }
        catch (Exception e)
        {
            return Map.of();
        }
    }

    @JsonIgnore
    public String getRegexForField(String fieldName)
    {
        return getExtractionRegexMap().get(fieldName);
    }

    @JsonIgnore
    public void setExtractionRegexMap(Map<String, String> regexMap)
    {
        if (regexMap == null || regexMap.isEmpty())
        {
            this.extractionRegexJson = null;
            this.regexGenerated = false;
        }
        else
        {
            try
            {
                com.fasterxml.jackson.databind.ObjectMapper mapper = 
                    new com.fasterxml.jackson.databind.ObjectMapper();
                this.extractionRegexJson = mapper.writeValueAsString(regexMap);
                this.regexGenerated = true;
            }
            catch (Exception e)
            {
                // Log l'erreur
                this.extractionRegexJson = null;
                this.regexGenerated = false;
            }
        }
    }

    @JsonIgnore
    public void setRegexForField(String fieldName, String regex)
    {
        Map<String, String> regexMap = new java.util.HashMap<>(getExtractionRegexMap());
        regexMap.put(fieldName, regex);
        setExtractionRegexMap(regexMap);
    }

    @JsonIgnore
    public boolean hasRegexGenerated()
    {
        return regexGenerated != null && regexGenerated;
    }
}