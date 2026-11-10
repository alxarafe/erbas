package com.alxarafe.erbas.knowledge.storage.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredDocument(UUID id, String ownerModule, Instant createdAt) { }
