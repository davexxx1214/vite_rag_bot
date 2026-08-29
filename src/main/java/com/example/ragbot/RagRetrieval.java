package com.example.ragbot;

import java.util.List;

record RagRetrieval(String context, List<RagSource> sources) {}
