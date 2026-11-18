package com.genai.first.ingeststatic.reader;

import com.genai.first.ingeststatic.config.data.RagConstants;
import com.genai.first.ingeststatic.exception.RagException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a PDF page by page in content-stream order (Story 1, Task 1).
 * <p>
 * Spring AI's PagePdfDocumentReader keeps the visual layout, so on a multi-column page the two columns
 * end up side by side on the same line and the text of different articles gets mixed. Reading in
 * content-stream order keeps each column together, and table rows stay on their own line.
 * One Document per page, with the page number in the "page_number" metadata (same key as Spring AI's readers).
 */
public class PdfTextPageReader {

    public List<Document> read(Resource pdfResource) {
        List<Document> pages = new ArrayList<>();
        try (InputStream inputStream = pdfResource.getInputStream();
             PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(inputStream))) {

            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(false); // content-stream order: one column after the other

            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                var text = stripper.getText(pdf);
                if (text == null || text.isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(RagConstants.PDF_PAGE_NUMBER, pageNumber);
                metadata.put("file_name", pdfResource.getFilename());
                pages.add(new Document(text, metadata));
            }
            return pages;
        } catch (IOException e) {
            throw new RagException("Could not read PDF " + pdfResource.getFilename(), e);
        }
    }
}
