package com.example.ragbot;

import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HelpDocumentService {
  private final List<HelpDoc> documents;

  public HelpDocumentService(RagProperties properties) throws IOException {
    this.documents = HelpDocs.load(properties.docsDirectory());
  }

  public List<HelpDoc> documents() {
    return documents;
  }
}
