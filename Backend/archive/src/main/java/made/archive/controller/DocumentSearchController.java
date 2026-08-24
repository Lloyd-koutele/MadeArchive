package made.archive.controller;

import lombok.RequiredArgsConstructor;
import made.archive.dto.SearchRequestDto;
import made.archive.dto.SearchResultDto;
import made.archive.service.document.DocumentSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class DocumentSearchController
{
    private final DocumentSearchService documentSearchService;

    @Secured("ROLE_USER")
    @PostMapping("/documents/search")
    public ResponseEntity<?> search(
        @RequestBody SearchRequestDto request,
        @AuthenticationPrincipal UserDetails userDetails)
    {
        try
        {
            SearchResultDto result = documentSearchService.search(request, userDetails);
            return ResponseEntity.ok(result);
        }
        catch (Exception e)
        {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}