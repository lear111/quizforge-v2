package cloud.treenewbee.quizforge.content.web;

import cloud.treenewbee.quizforge.content.application.ImportService;
import cloud.treenewbee.quizforge.content.domain.ImportFailure;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ImportController {
    private final ImportService imports;
    public ImportController(ImportService imports) { this.imports = imports; }
    public record ImportResponse(List<ImportService.ImportedCollection> collections) {}

    @PostMapping(value = "/api/library/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse importArchive(@RequestPart("file") MultipartFile file, Principal principal) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "";
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".qbank")) {
            throw new ImportFailure("INVALID_LIBRARY_ARCHIVE", "请上传 .qbank 题库包");
        }
        try (var input = file.getInputStream()) {
            return new ImportResponse(imports.importArchive(principal.getName(), filename, input));
        }
    }
}
